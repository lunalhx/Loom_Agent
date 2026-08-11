package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.PlanSubmissionHandler;
import cn.lunalhx.ai.domain.agent.adapter.port.PlanSubmissionResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.agent.model.entity.Plan;
import cn.lunalhx.ai.domain.agent.model.entity.PlanRevision;
import cn.lunalhx.ai.domain.agent.model.entity.PlanSubmission;
import cn.lunalhx.ai.domain.agent.model.entity.PlanSubmissionTransaction;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Runtime-owned Plan Submission transaction for the file-backed Session.
 * The Session write-ahead record is persisted first; the visible Plan
 * aggregate is committed only after the terminal Run is durable. Recovery
 * completes or rolls back a pending transaction after an interruption.
 */
public final class FilePlanSubmissionHandler implements PlanSubmissionHandler {

    private static final String NEW_TARGET = "NEW";

    private final AgentSessionRepository sessionRepository;
    private final AgentRunRepository runRepository;
    private final ObjectMapper mapper;

    public FilePlanSubmissionHandler(AgentSessionRepository sessionRepository,
                                     AgentRunRepository runRepository,
                                     ObjectMapper mapper) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository,
                "sessionRepository must not be null");
        this.runRepository = Objects.requireNonNull(runRepository,
                "runRepository must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public PlanSubmissionResult prepare(AgentContext context) {
        PlanSubmissionResult authorization = validateAuthorization(context);
        if (authorization != null) {
            return authorization;
        }
        try {
            return prepareCurrent(context);
        } catch (Exception e) {
            return PlanSubmissionResult.conflict(
                    "Plan Conflict: persistence or validation failed: " + e.getMessage());
        }
    }

    @Override
    public PlanSubmissionResult commit(AgentContext context) {
        PlanSubmissionResult authorization = validateAuthorization(context);
        if (authorization != null) {
            return authorization;
        }
        try {
            AgentRun run = runRepository.find(context.getRunId()).orElse(null);
            if (run == null || run.getStatus() != AgentRunStatus.COMPLETED
                    || !"PLAN_SUBMITTED".equals(run.getStopReason())) {
                return PlanSubmissionResult.conflict(
                        "Plan Conflict: terminal Run outcome is not durable");
            }
            return commitPending(context.getSessionId(), context.getRunId(),
                    context.getResolvedWorkspace(), context.isEvidenceDrift());
        } catch (Exception e) {
            return PlanSubmissionResult.conflict(
                    "Plan Conflict: terminal persistence failed: " + e.getMessage());
        }
    }

    /** Recover pending transactions before exposing a Session through the CLI. */
    public AgentSession recoverPending(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return null;
        }
        AgentSession initial = readSession(sessionId);
        if (initial == null) {
            return null;
        }
        Path workspaceRoot = workspaceRoot(initial);
        try {
            AgentSession current = readSession(sessionId);
            if (current == null || current.getPendingPlanSubmission() == null) {
                return current;
            }
            PlanSubmissionTransaction transaction = current.getPendingPlanSubmission();
            AgentRun run = runRepository.find(transaction.getRunId()).orElse(null);
            if (run != null && run.getStatus() == AgentRunStatus.COMPLETED
                    && "PLAN_SUBMITTED".equals(run.getStopReason())) {
                PlanSubmissionResult result = commitPending(sessionId, transaction.getRunId(),
                        workspaceRoot, Boolean.TRUE.equals(run.getEvidenceDrift()));
                if (result.outcome() != PlanSubmissionResult.Outcome.SUBMITTED) {
                    markPlanConflictRun(run);
                }
            } else {
                rollbackPending(current);
                markPlanConflictRun(run);
            }
            return readSession(sessionId);
        } catch (Exception e) {
            return readSession(sessionId);
        }
    }

    @Override
    public void abort(AgentContext context) {
        if (context == null || StringUtils.isBlank(context.getSessionId())
                || StringUtils.isBlank(context.getRunId())) {
            return;
        }
        try {
            AgentSession current = readSession(context.getSessionId());
            if (current != null && current.getPendingPlanSubmission() != null
                    && Objects.equals(context.getRunId(),
                    current.getPendingPlanSubmission().getRunId())) {
                rollbackPending(current);
            }
        } catch (Exception ignored) {
            // The terminal Plan Conflict remains the visible outcome.
        }
    }

    private PlanSubmissionResult validateAuthorization(AgentContext context) {
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

    private PlanSubmissionResult prepareCurrent(AgentContext context) {
        AgentSession current = readSession(context.getSessionId());
        if (current == null) {
            return PlanSubmissionResult.conflict(
                    "Plan Conflict: current Session does not exist");
        }
        if (current.getPendingPlanSubmission() != null) {
            return PlanSubmissionResult.conflict(
                    "Plan Conflict: another Plan Submission is already pending");
        }
        if (current.getPlanStateVersion() != context.getPlanStateVersion()) {
            return PlanSubmissionResult.conflict(
                    "Plan Conflict: Plan state version changed during the Run");
        }
        if (context.isEvidenceDrift()) {
            return PlanSubmissionResult.conflict(
                    "Plan Conflict: Plan Evidence drifted during the Run");
        }
        if (!freshEvidence(context.getResolvedWorkspace(), context.getEvidenceReceipts(),
                context.getRootRunId())) {
            return PlanSubmissionResult.conflict(
                    "Plan Conflict: current Run evidence provenance or freshness is invalid");
        }

        PlanSubmission submission = context.getDecision().getPlanSubmission();
        if (StringUtils.isBlank(submission.getTitle())
                || StringUtils.isBlank(submission.getBody())
                || submission.getDependencies() == null) {
            return PlanSubmissionResult.rejected("Plan Submission payload is invalid");
        }

        AgentSession candidate;
        try {
            candidate = mapper.convertValue(current, AgentSession.class);
        } catch (IllegalArgumentException e) {
            return PlanSubmissionResult.conflict(
                    "Plan Conflict: Session aggregate could not be copied: " + e.getMessage());
        }

        Instant now = Instant.now();
        String digest;
        try {
            digest = contentDigest(submission);
        } catch (Exception e) {
            return PlanSubmissionResult.conflict(
                    "Plan Conflict: Plan content digest could not be computed: " + e.getMessage());
        }

        boolean newTarget = NEW_TARGET.equals(context.getPlanTarget());
        Plan plan;
        int nextRevision;
        List<EvidenceReceipt> candidateBasis;
        if (newTarget) {
            if (StringUtils.isNotBlank(current.getCurrentPlanId())) {
                return PlanSubmissionResult.conflict(
                        "Plan Conflict: the NEW target requires no Current Plan selection");
            }
            plan = Plan.builder()
                    .planId("plan_" + UUID.randomUUID())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            nextRevision = 1;
            candidateBasis = List.copyOf(context.getEvidenceReceipts());
        } else {
            if (StringUtils.isBlank(context.getPlanTarget())
                    || !Objects.equals(current.getCurrentPlanId(), context.getPlanTarget())) {
                return PlanSubmissionResult.conflict(
                        "Plan Conflict: selected Plan changed during the Run");
            }
            Plan selected = findPlan(candidate, context.getPlanTarget());
            PlanRevision previous = selected == null ? null : selected.currentRevision();
            if (previous == null || context.getPlanRevision() == null
                    || !Objects.equals(context.getPlanRevision(), previous.getRevision())) {
                return PlanSubmissionResult.conflict(
                        "Plan Conflict: the selected Plan head changed during the Run");
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
        if (!freshPlanEvidence(context.getResolvedWorkspace(), plan)) {
            return PlanSubmissionResult.conflict(
                    "Plan Conflict: relied-on repository evidence is stale");
        }
        candidate.setPendingPlanSubmission(PlanSubmissionTransaction.builder()
                .transactionId("plan_tx_" + UUID.randomUUID())
                .runId(context.getRunId())
                .expectedPlanStateVersion(current.getPlanStateVersion())
                .plan(plan)
                .build());
        if (!saveIfCurrent(candidate, current.getUpdatedAt())) {
            return PlanSubmissionResult.conflict(
                    "Plan Conflict: Session changed before Plan preparation");
        }
        return PlanSubmissionResult.prepared(plan.getPlanId(), nextRevision);
    }

    private PlanSubmissionResult commitPending(String sessionId, String runId,
                                               Path workspaceRoot,
                                               boolean evidenceDrift) {
        AgentSession current = readSession(sessionId);
        if (current == null || current.getPendingPlanSubmission() == null) {
            return PlanSubmissionResult.conflict(
                    "Plan Conflict: pending Plan Submission is missing");
        }
        PlanSubmissionTransaction transaction = current.getPendingPlanSubmission();
        if (!Objects.equals(runId, transaction.getRunId())
                || transaction.getPlan() == null) {
            return PlanSubmissionResult.conflict(
                "Plan Conflict: pending Plan Submission belongs to another Run");
        }
        Plan plan = transaction.getPlan();
        PlanRevision submittedRevision = plan.currentRevision();
        Plan existing = findPlan(current, plan.getPlanId());
        if (submittedRevision != null && existing != null
                && existing.currentRevision() != null
                && Objects.equals(existing.currentRevision().getRevision(), submittedRevision.getRevision())
                && Objects.equals(existing.currentRevision().getContentDigest(), submittedRevision.getContentDigest())
                && Objects.equals(current.getCurrentPlanId(), plan.getPlanId())
                && current.getPlanStateVersion() == transaction.getExpectedPlanStateVersion() + 1) {
            AgentSession clear = copy(current);
            clear.setPendingPlanSubmission(null);
            if (!saveIfCurrent(clear, current.getUpdatedAt())) {
                return PlanSubmissionResult.conflict(
                        "Plan Conflict: Session changed while finalizing Plan");
            }
            return PlanSubmissionResult.submitted(plan.getPlanId(), submittedRevision.getRevision());
        }
        boolean newTarget = submittedRevision != null && submittedRevision.getRevision() == 1;
        boolean targetMatches = newTarget
                ? StringUtils.isBlank(current.getCurrentPlanId()) && existing == null
                : StringUtils.isNotBlank(current.getCurrentPlanId())
                && Objects.equals(current.getCurrentPlanId(), plan.getPlanId())
                && existing != null
                && existing.currentRevision() != null
                && existing.currentRevision().getRevision() == submittedRevision.getRevision() - 1;
        if (submittedRevision == null
                || current.getPlanStateVersion() != transaction.getExpectedPlanStateVersion()
                || !targetMatches) {
            rollbackPending(current);
            return PlanSubmissionResult.conflict(
                    "Plan Conflict: Plan target or state changed before commit");
        }
        if (evidenceDrift || !freshPlanEvidence(workspaceRoot, plan)) {
            rollbackPending(current);
            return PlanSubmissionResult.conflict(
                    "Plan Conflict: relied-on repository evidence is stale at commit");
        }
        AgentSession candidate = copy(current);
        List<Plan> plans = new ArrayList<>();
        if (candidate.getPlans() != null) {
            plans.addAll(candidate.getPlans());
        }
        if (newTarget) {
            plans.add(plan);
        } else {
            for (int i = 0; i < plans.size(); i++) {
                if (plans.get(i) != null && Objects.equals(plans.get(i).getPlanId(), plan.getPlanId())) {
                    plans.set(i, plan);
                    break;
                }
            }
        }
        candidate.setPlans(List.copyOf(plans));
        candidate.setCurrentPlanId(plan.getPlanId());
        candidate.setPlanStateVersion(current.getPlanStateVersion() + 1);
        candidate.setPendingPlanSubmission(null);
        if (!saveIfCurrent(candidate, current.getUpdatedAt())) {
            return PlanSubmissionResult.conflict(
                    "Plan Conflict: Session changed while finalizing Plan");
        }
        return PlanSubmissionResult.submitted(plan.getPlanId(), submittedRevision.getRevision());
    }

    private void rollbackPending(AgentSession current) {
        AgentSession candidate = copy(current);
        candidate.setPendingPlanSubmission(null);
        saveIfCurrent(candidate, current.getUpdatedAt());
    }

    private void markPlanConflictRun(AgentRun run) {
        if (run == null || run.getStatus() == AgentRunStatus.FAILED) {
            return;
        }
        run.setStatus(AgentRunStatus.FAILED);
        run.setStopReason("PLAN_CONFLICT");
        run.setFinalAnswer("Plan Conflict: pending Plan Submission was abandoned");
        try {
            runRepository.save(run);
        } catch (Exception ignored) {
            // Session recovery still removes the invisible pending aggregate.
        }
    }

    private boolean freshPlanEvidence(Path workspaceRoot, Plan plan) {
        if (plan == null || plan.currentRevision() == null) {
            return false;
        }
        return freshEvidence(workspaceRoot, plan.currentRevision().getPlanBasis());
    }

    private Plan findPlan(AgentSession session, String planId) {
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

    private AgentSession copy(AgentSession session) {
        return mapper.convertValue(session, AgentSession.class);
    }

    private AgentSession readSession(String sessionId) {
        try {
            return sessionRepository.find(sessionId).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean saveIfCurrent(AgentSession candidate, Instant expectedUpdatedAt) {
        try {
            return sessionRepository.saveIfUnchanged(candidate, expectedUpdatedAt);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean freshEvidence(Path workspaceRoot, List<EvidenceReceipt> receipts) {
        return freshEvidence(workspaceRoot, receipts, null);
    }

    private boolean freshEvidence(Path workspaceRoot, List<EvidenceReceipt> receipts,
                                  String rootRunId) {
        if (receipts == null || receipts.isEmpty()) {
            return true;
        }
        for (EvidenceReceipt receipt : receipts) {
            if (receipt == null
                    || StringUtils.isBlank(receipt.getSourceRunId())
                    || (StringUtils.isNotBlank(rootRunId)
                    && !Objects.equals(receipt.getRootRunId(), rootRunId))
                    || !PlanEvidenceVerifier.matches(workspaceRoot, receipt)) {
                return false;
            }
        }
        return true;
    }

    private Path workspaceRoot(AgentSession session) {
        if (session == null || StringUtils.isBlank(session.getWorkspaceRoot())) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        return Path.of(session.getWorkspaceRoot()).toAbsolutePath().normalize();
    }

    private String contentDigest(PlanSubmission submission) throws Exception {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("title", submission.getTitle());
        canonical.put("body", submission.getBody());
        canonical.put("dependencies", submission.getDependencies());
        return DigestUtils.sha256Hex(mapper.writeValueAsBytes(canonical));
    }
}
