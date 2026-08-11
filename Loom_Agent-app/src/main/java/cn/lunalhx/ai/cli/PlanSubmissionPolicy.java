package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.PlanSubmissionResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.agent.model.entity.Plan;
import cn.lunalhx.ai.domain.agent.model.entity.PlanRevision;
import cn.lunalhx.ai.domain.agent.model.entity.PlanSubmission;
import cn.lunalhx.ai.domain.agent.model.entity.PlanSubmissionTransaction;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Validates a Plan Submission and constructs its pending Session aggregate. */
final class PlanSubmissionPolicy {

    private static final String NEW_TARGET = "NEW";

    private final ObjectMapper mapper;

    PlanSubmissionPolicy(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    PlanSubmissionResult authorize(AgentContext context) {
        if (context == null || context.getDecision() == null
                || context.getDecision().getPlanSubmission() == null) {
            return PlanSubmissionResult.rejected("Plan Submission payload is missing");
        }
        if (context.getCollaborationMode() != CollaborationMode.PLAN
                || StringUtils.isNotBlank(context.getParentRunId())
                || StringUtils.isBlank(context.getRunId())
                || StringUtils.isBlank(context.getRootRunId())
                || !Objects.equals(context.getRunId(), context.getRootRunId())) {
            return PlanSubmissionResult.rejected(
                    "Plan Submission is allowed only for a root PLAN Run");
        }
        if (StringUtils.isBlank(context.getPlanTarget())) {
            return PlanSubmissionResult.conflict(
                    "Plan Conflict: the Run has no fixed Plan target");
        }
        if (StringUtils.isBlank(context.getSessionId())) {
            return PlanSubmissionResult.conflict(
                    "Plan Conflict: the Run has no Session identity");
        }
        return null;
    }

    Preparation prepare(AgentSession current, AgentContext context) {
        if (current == null) {
            return failed(PlanSubmissionResult.conflict(
                    "Plan Conflict: current Session does not exist"));
        }
        if (current.getPendingPlanSubmission() != null) {
            return failed(PlanSubmissionResult.conflict(
                    "Plan Conflict: another Plan Submission is already pending"));
        }
        if (current.getPlanStateVersion() != context.getPlanStateVersion()) {
            return failed(PlanSubmissionResult.conflict(
                    "Plan Conflict: Plan state version changed during the Run"));
        }
        if (context.isEvidenceDrift()) {
            return failed(PlanSubmissionResult.conflict(
                    "Plan Conflict: Plan Evidence drifted during the Run"));
        }
        if (!PlanEvidenceVerifier.matchesAll(context.getResolvedWorkspace(),
                context.getEvidenceReceipts(), context.getRootRunId())) {
            return failed(PlanSubmissionResult.conflict(
                    "Plan Conflict: current Run evidence provenance or freshness is invalid"));
        }

        PlanSubmission submission = context.getDecision().getPlanSubmission();
        if (StringUtils.isBlank(submission.getTitle())
                || StringUtils.isBlank(submission.getBody())
                || submission.getDependencies() == null) {
            return failed(PlanSubmissionResult.rejected(
                    "Plan Submission payload is invalid"));
        }

        AgentSession candidate;
        try {
            candidate = mapper.convertValue(current, AgentSession.class);
        } catch (IllegalArgumentException e) {
            return failed(PlanSubmissionResult.conflict(
                    "Plan Conflict: Session aggregate could not be copied: " + e.getMessage()));
        }

        Instant now = Instant.now();
        String digest;
        try {
            digest = contentDigest(submission);
        } catch (Exception e) {
            return failed(PlanSubmissionResult.conflict(
                    "Plan Conflict: Plan content digest could not be computed: " + e.getMessage()));
        }

        boolean newTarget = NEW_TARGET.equals(context.getPlanTarget());
        Plan plan;
        int nextRevision;
        List<EvidenceReceipt> candidateBasis;
        if (newTarget) {
            if (StringUtils.isNotBlank(current.getCurrentPlanId())) {
                return failed(PlanSubmissionResult.conflict(
                        "Plan Conflict: the NEW target requires no Current Plan selection"));
            }
            plan = Plan.builder()
                    .planId("plan_" + UUID.randomUUID())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            nextRevision = 1;
            candidateBasis = List.copyOf(context.getEvidenceReceipts());
        } else {
            if (!Objects.equals(current.getCurrentPlanId(), context.getPlanTarget())) {
                return failed(PlanSubmissionResult.conflict(
                        "Plan Conflict: selected Plan changed during the Run"));
            }
            Plan selected = findPlan(candidate, context.getPlanTarget());
            PlanRevision previous = selected == null ? null : selected.currentRevision();
            if (previous == null || context.getPlanRevision() == null
                    || !Objects.equals(context.getPlanRevision(), previous.getRevision())) {
                return failed(PlanSubmissionResult.conflict(
                        "Plan Conflict: the selected Plan head changed during the Run"));
            }
            plan = mapper.convertValue(selected, Plan.class);
            nextRevision = previous.getRevision() + 1;
            candidateBasis = mergeBasis(previous.getPlanBasis(), context.getEvidenceReceipts());
        }

        PlanRevision revision = PlanRevision.builder()
                .revision(nextRevision)
                .title(submission.getTitle())
                .body(submission.getBody())
                .dependencies(List.copyOf(submission.getDependencies()))
                .createdAt(now)
                .updatedAt(now)
                .contentDigest(digest)
                .planBasis(candidateBasis)
                .build();
        if (newTarget) {
            plan.setRevisions(List.of(revision));
        } else {
            List<PlanRevision> revisions = new ArrayList<>(plan.getRevisions());
            revisions.add(revision);
            plan.setRevisions(List.copyOf(revisions));
            plan.setUpdatedAt(now);
        }
        if (!PlanEvidenceVerifier.matchesPlan(context.getResolvedWorkspace(), plan)) {
            return failed(PlanSubmissionResult.conflict(
                    "Plan Conflict: relied-on repository evidence is stale"));
        }
        candidate.setPendingPlanSubmission(PlanSubmissionTransaction.builder()
                .transactionId("plan_tx_" + UUID.randomUUID())
                .runId(context.getRunId())
                .expectedPlanStateVersion(current.getPlanStateVersion())
                .plan(plan)
                .build());
        return new Preparation(candidate,
                PlanSubmissionResult.prepared(plan.getPlanId(), nextRevision));
    }

    Plan findPlan(AgentSession session, String planId) {
        if (session == null || session.getPlans() == null || StringUtils.isBlank(planId)) {
            return null;
        }
        for (Plan plan : session.getPlans()) {
            if (plan != null && Objects.equals(planId, plan.getPlanId())) {
                return plan;
            }
        }
        return null;
    }

    private List<EvidenceReceipt> mergeBasis(List<EvidenceReceipt> inherited,
                                             List<EvidenceReceipt> currentRun) {
        Map<String, EvidenceReceipt> merged = new LinkedHashMap<>();
        if (inherited != null) {
            for (EvidenceReceipt receipt : inherited) {
                if (receipt != null) {
                    merged.put(receipt.getEvidenceKey(), receipt);
                }
            }
        }
        if (currentRun != null) {
            for (EvidenceReceipt receipt : currentRun) {
                if (receipt != null) {
                    merged.put(receipt.getEvidenceKey(), receipt);
                }
            }
        }
        return List.copyOf(merged.values());
    }

    private String contentDigest(PlanSubmission submission) throws Exception {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("title", submission.getTitle());
        canonical.put("body", submission.getBody());
        canonical.put("dependencies", submission.getDependencies());
        return DigestUtils.sha256Hex(mapper.writeValueAsBytes(canonical));
    }

    private Preparation failed(PlanSubmissionResult result) {
        return new Preparation(null, result);
    }

    record Preparation(AgentSession candidate, PlanSubmissionResult result) {

        boolean ready() {
            return candidate != null
                    && result.outcome() == PlanSubmissionResult.Outcome.PREPARED;
        }
    }
}
