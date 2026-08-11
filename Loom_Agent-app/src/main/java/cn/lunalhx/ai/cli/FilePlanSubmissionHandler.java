package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.PlanSubmissionHandler;
import cn.lunalhx.ai.domain.agent.adapter.port.PlanSubmissionResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.entity.Plan;
import cn.lunalhx.ai.domain.agent.model.entity.PlanRevision;
import cn.lunalhx.ai.domain.agent.model.entity.PlanSubmissionTransaction;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runtime-owned Plan Submission transaction for the file-backed Session.
 * The Session write-ahead record is persisted first; the visible Plan
 * aggregate is committed only after the terminal Run is durable. Recovery
 * completes or rolls back a pending transaction after an interruption.
 */
public final class FilePlanSubmissionHandler implements PlanSubmissionHandler {

    private final AgentSessionRepository sessionRepository;
    private final AgentRunRepository runRepository;
    private final ObjectMapper mapper;
    private final PlanSubmissionPolicy submissionPolicy;

    public FilePlanSubmissionHandler(AgentSessionRepository sessionRepository,
                                     AgentRunRepository runRepository,
                                     ObjectMapper mapper) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository,
                "sessionRepository must not be null");
        this.runRepository = Objects.requireNonNull(runRepository,
                "runRepository must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.submissionPolicy = new PlanSubmissionPolicy(mapper);
    }

    @Override
    public PlanSubmissionResult prepare(AgentContext context) {
        PlanSubmissionResult authorization = submissionPolicy.authorize(context);
        if (authorization != null) {
            return authorization;
        }
        try {
            AgentSession current = readSession(context.getSessionId());
            PlanSubmissionPolicy.Preparation preparation =
                    submissionPolicy.prepare(current, context);
            if (!preparation.ready()) {
                return preparation.result();
            }
            if (!saveIfCurrent(preparation.candidate(), current.getUpdatedAt())) {
                return PlanSubmissionResult.conflict(
                        "Plan Conflict: Session changed before Plan preparation");
            }
            return preparation.result();
        } catch (Exception e) {
            return PlanSubmissionResult.conflict(
                    "Plan Conflict: persistence or validation failed: " + e.getMessage());
        }
    }

    @Override
    public PlanSubmissionResult commit(AgentContext context) {
        PlanSubmissionResult authorization = submissionPolicy.authorize(context);
        if (authorization != null) {
            return authorization;
        }
        try {
            AgentRun run = runRepository.find(context.getRunId()).orElse(null);
            if (run == null || run.getStatus() != AgentRunStatus.COMPLETED
                    || !AgentStopReason.PLAN_SUBMITTED.name().equals(run.getStopReason())) {
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
                    && AgentStopReason.PLAN_SUBMITTED.name().equals(run.getStopReason())) {
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
        Plan existing = submissionPolicy.findPlan(current, plan.getPlanId());
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
        if (evidenceDrift || !PlanEvidenceVerifier.matchesPlan(workspaceRoot, plan)) {
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
        run.setStopReason(AgentStopReason.PLAN_CONFLICT.name());
        run.setFinalAnswer("Plan Conflict: pending Plan Submission was abandoned");
        try {
            runRepository.save(run);
        } catch (Exception ignored) {
            // Session recovery still removes the invisible pending aggregate.
        }
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

    private Path workspaceRoot(AgentSession session) {
        if (session == null || StringUtils.isBlank(session.getWorkspaceRoot())) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        return Path.of(session.getWorkspaceRoot()).toAbsolutePath().normalize();
    }

}
