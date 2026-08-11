package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.state.AgentBudgetState;
import cn.lunalhx.ai.domain.agent.model.state.AgentIdentity;
import cn.lunalhx.ai.domain.agent.model.state.AgentRuntimeState;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunKind;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.BudgetState;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Explicit run lifecycle persistence, replacing the hidden checkpoint hook.
 * Handles run init, model-attempt update, safe post-tool checkpoint,
 * approval/user-input pauses, and terminal complete/stopped/failed/cancelled.
 */
public final class AgentRunLifecycle {

    private final AgentRunRepository runRepository;
    private final AgentCheckpointRepository checkpointRepository;

    public AgentRunLifecycle(AgentRunRepository runRepository,
                             AgentCheckpointRepository checkpointRepository) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository must not be null");
        this.checkpointRepository = Objects.requireNonNull(checkpointRepository, "checkpointRepository must not be null");
    }

    // ================================================================
    // Init / progress
    // ================================================================

    public List<AgentEvent> initializeRun(AgentContext context) {
        saveRun(context, AgentNodeNames.PROMPT_BUILD, AgentRunStatus.RUNNING);
        AgentCheckpoint checkpoint = saveCheckpoint(context, AgentNodeNames.PROMPT_BUILD, "run_init");
        context.setCheckpointVersion(checkpoint.getVersion());
        return List.of(AgentEvent.builder()
                .type(AgentEventType.CHECKPOINT_SAVED)
                .runId(context.identity().runId())
                .requestId(context.identity().requestId())
                .conversationId(context.identity().conversationId())
                .workspace(context.environment().workspaceDisplayName())
                .node(AgentNodeNames.PROMPT_BUILD)
                .toolSteps(context.runtime().toolSteps())
                .modelAttempts(context.runtime().modelAttempts())
                .lastTool(context.runtime().lastTool())
                .checkpointVersion(checkpoint.getVersion())
                .build());
    }

    public void recordModelAttempt(AgentContext context) {
        saveRun(context, AgentNodeNames.MODEL_CALL, AgentRunStatus.RUNNING);
    }

    /** Post-tool checkpoint: the snapshot already contains the sanitized
     *  result, history, memory and ledger. The next recovery node is always
     *  {@code prompt_build} and the running context's current node is never
     *  mutated by the save. */
    public List<AgentEvent> checkpointAfterTool(AgentContext context) {
        AgentCheckpoint checkpoint = saveCheckpoint(context, AgentNodeNames.PROMPT_BUILD, "after_tool");
        context.setCheckpointVersion(checkpoint.getVersion());
        saveRun(context, AgentNodeNames.PROMPT_BUILD, AgentRunStatus.RUNNING);
        return List.of(AgentEvent.builder()
                .type(AgentEventType.CHECKPOINT_SAVED)
                .runId(context.identity().runId())
                .requestId(context.identity().requestId())
                .conversationId(context.identity().conversationId())
                .workspace(context.environment().workspaceDisplayName())
                .node(AgentNodeNames.PROMPT_BUILD)
                .toolSteps(context.runtime().toolSteps())
                .modelAttempts(context.runtime().modelAttempts())
                .lastTool(context.runtime().lastTool())
                .checkpointVersion(checkpoint.getVersion())
                .build());
    }

    public List<AgentEvent> pauseForUserInput(AgentContext context) {
        AgentCheckpoint checkpoint = saveCheckpoint(context, AgentNodeNames.PROMPT_BUILD, "pause_user_input");
        context.setCheckpointVersion(checkpoint.getVersion());
        saveRun(context, AgentNodeNames.PROMPT_BUILD, AgentRunStatus.WAITING_USER_INPUT);
        return List.of(AgentEvent.builder()
                .type(AgentEventType.CHECKPOINT_SAVED)
                .runId(context.identity().runId())
                .requestId(context.identity().requestId())
                .conversationId(context.identity().conversationId())
                .workspace(context.environment().workspaceDisplayName())
                .node(AgentNodeNames.PROMPT_BUILD)
                .toolSteps(context.runtime().toolSteps())
                .modelAttempts(context.runtime().modelAttempts())
                .lastTool(context.runtime().lastTool())
                .checkpointVersion(checkpoint.getVersion())
                .build());
    }

    // ================================================================
    // Terminal
    // ================================================================

    public void complete(AgentContext context) {
        saveFinalCheckpoint(context, "run_complete");
        saveRun(context, context.runtime().currentNode(), AgentRunStatus.COMPLETED);
    }

    public void stopped(AgentContext context) {
        saveFinalCheckpoint(context, "run_stopped");
        saveRun(context, context.runtime().currentNode(), AgentRunStatus.STOPPED);
    }

    public void failed(AgentContext context) {
        saveFinalCheckpoint(context, "run_failed");
        saveRun(context, context.runtime().currentNode(), AgentRunStatus.FAILED);
    }

    public void cancelled(AgentContext context) {
        saveFinalCheckpoint(context, "run_cancelled");
        saveRun(context, context.runtime().currentNode(), AgentRunStatus.STOPPED);
    }

    /** Terminal checkpoint so session sync captures the final ledger (incl. the
     *  final answer system note) and the final working memory. */
    private void saveFinalCheckpoint(AgentContext context, String reason) {
        try {
            AgentCheckpoint checkpoint = saveCheckpoint(context, context.runtime().currentNode(), reason);
            context.setCheckpointVersion(checkpoint.getVersion());
        } catch (Exception ignored) {
            // checkpoint persistence must never mask the terminal outcome
        }
    }

    // ================================================================
    // Internals
    // ================================================================

    private AgentCheckpoint saveCheckpoint(AgentContext context, String currentNode, String reason) {
        AgentContextSnapshot snapshot = AgentContextSnapshot.from(context);
        return checkpointRepository.save(AgentCheckpoint.builder()
                .runId(context.identity().runId())
                .currentNode(currentNode)
                .contextSnapshot(snapshot)
                .reason(reason)
                .build());
    }

    private void saveRun(AgentContext context, String currentNode, AgentRunStatus fallbackStatus) {
        AgentRuntimeState runtime = context.runtime();
        AgentIdentity id = context.identity();
        AgentBudgetState budget = context.budget();
        BudgetState budgetState = budget.budgetState();
        cn.lunalhx.ai.domain.agent.model.state.AgentRunDefinition def = context.runDefinition();

        AgentRunStatus status = resolveStatus(runtime, fallbackStatus);

        runRepository.save(AgentRun.builder()
                .runId(id.runId())
                .sessionId(context.getSessionId())
                .parentRunId(id.parentRunId())
                .rootRunId(StringUtils.defaultIfBlank(id.rootRunId(), id.runId()))
                .requestId(id.requestId())
                .conversationId(id.conversationId())
                .runKind(StringUtils.isBlank(id.parentRunId()) ? AgentRunKind.ROOT : AgentRunKind.CHILD)
                .runModeSnapshot(def.collaborationMode())
                .depth(id.agentDepth())
                .maxSteps(def.maxSteps())
                .question(context.runDefinition().question())
                .workspace(context.environment().workspaceDisplayName())
                .planTarget(def.planTarget())
                .planStateVersion(def.planStateVersion())
                .status(status)
                .currentNode(currentNode)
                .toolSteps(runtime.toolSteps())
                .modelAttempts(runtime.modelAttempts())
                .lastTool(runtime.lastTool())
                .stopReason(runtime.stopReason() == null ? null : runtime.stopReason().name())
                .finalAnswer(runtime.finalAnswer())
                .checkpointVersion(runtime.checkpointVersion())
                .summaryJson(runtime.finalAnswer())
                .blockedReason(StringUtils.defaultIfBlank(
                        context.budget().budgetBlockedReason(), context.recovery().contextBlockedReason()))
                .evidenceReceipts(context.getEvidenceReceipts())
                .evidenceDrift(context.isEvidenceDrift())
                .usedTokens(budgetState.usedTokens())
                .estimatedCost(budgetState.estimatedCost())
                .updatedAt(Instant.now())
                .build());
    }

    private AgentRunStatus resolveStatus(AgentRuntimeState runtime, AgentRunStatus fallback) {
        if (StringUtils.isNotBlank(runtime.errorMessage()) || runtime.errorCode() != null) {
            return AgentRunStatus.FAILED;
        }
        AgentStopReason reason = runtime.stopReason();
        if (reason == AgentStopReason.USER_CANCELLED
                || reason == AgentStopReason.STEP_LIMIT_REACHED
                || reason == AgentStopReason.RETRY_LIMIT_REACHED) {
            return AgentRunStatus.STOPPED;
        }
        if (reason == AgentStopReason.FINAL_ANSWER_RETURNED
                || reason == AgentStopReason.PLAN_SUBMITTED) {
            return AgentRunStatus.COMPLETED;
        }
        if (reason == AgentStopReason.BUDGET_EXCEEDED
                || reason == AgentStopReason.MODEL_ERROR
                || reason == AgentStopReason.TIMEOUT
                || reason == AgentStopReason.CONTEXT_OVERFLOW
                || reason == AgentStopReason.RUNTIME_SCHEMA_MISMATCH
                || reason == AgentStopReason.PLAN_CONFLICT
                || reason == AgentStopReason.PLAN_SUBMISSION_REJECTED) {
            return AgentRunStatus.FAILED;
        }
        return fallback;
    }
}
