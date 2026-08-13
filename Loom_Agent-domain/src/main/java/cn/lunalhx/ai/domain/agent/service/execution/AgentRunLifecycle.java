package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AttemptLeaseRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationHistoryRepository;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistory;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryEntry;
import cn.lunalhx.ai.domain.agent.model.entity.ToolExecutionMarker;
import cn.lunalhx.ai.domain.agent.model.state.AgentBudgetState;
import cn.lunalhx.ai.domain.agent.model.state.AgentIdentity;
import cn.lunalhx.ai.domain.agent.model.state.AgentRuntimeState;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunKind;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.BudgetState;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
import cn.lunalhx.ai.domain.agent.service.ledger.ControlUpdateTexts;
import cn.lunalhx.ai.domain.tool.service.ObservationTools;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Explicit run lifecycle persistence, replacing the hidden checkpoint hook.
 * Handles run init, model-attempt update, safe post-tool checkpoint,
 * approval/user-input pauses, and terminal complete/stopped/failed/cancelled.
 *
 * <p>Conversation History is persisted before each AgentCheckpoint so the
 * checkpoint may only store an exact History anchor.
 */
public final class AgentRunLifecycle {

    static final String EXECUTION_WINDOW_REASON = "execution_window";

    private final AgentRunRepository runRepository;
    private final AgentCheckpointRepository checkpointRepository;
    private final ConversationHistoryRepository historyRepository;
    private final AttemptLeaseRepository leaseRepository;
    private final ConversationHistoryAppendService historyAppend = new ConversationHistoryAppendService();

    public AgentRunLifecycle(AgentRunRepository runRepository,
                             AgentCheckpointRepository checkpointRepository,
                             ConversationHistoryRepository historyRepository,
                             AttemptLeaseRepository leaseRepository) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository must not be null");
        this.checkpointRepository = Objects.requireNonNull(checkpointRepository, "checkpointRepository must not be null");
        this.historyRepository = Objects.requireNonNull(historyRepository, "historyRepository must not be null");
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository must not be null");
    }

    AttemptLeaseRepository attemptLeaseRepository() {
        return leaseRepository;
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

    /** Mark a restored unfinished Run as running again at {@code prompt_build}. */
    public List<AgentEvent> resumeRunning(AgentContext context) {
        saveRun(context, AgentNodeNames.PROMPT_BUILD, AgentRunStatus.RUNNING);
        return List.of();
    }

    public void recordModelAttempt(AgentContext context) {
        saveRun(context, AgentNodeNames.MODEL_CALL, AgentRunStatus.RUNNING);
    }

    /** Persist History after prompt build so a lost Attempt still has durable user facts. */
    public void persistHistoryAfterPrompt(AgentContext context) {
        persistConversationHistory(context);
    }

    /** Persist the sanitized Tool Call and open the execution window before adapter invocation. */
    public List<AgentEvent> openExecutionWindow(AgentContext context) {
        var toolCall = context.getToolCall();
        if (toolCall == null || !ObservationTools.isObservation(toolCall.getName())) {
            return List.of();
        }
        String toolCallId = StringUtils.defaultIfBlank(toolCall.getToolCallId(), "unknown");
        String sanitizedInput = context.getDecision() == null || context.getDecision().getInput() == null
                ? "{}" : context.getDecision().getInput().toString();
        historyAppend.appendToolCall(context, toolCall.getName(), toolCallId, sanitizedInput,
                ConversationHistoryInitializer.eventKey(context.getRunId(), toolCallId, "tool_call"));
        persistConversationHistory(context);
        context.setExecutionWindow(ToolExecutionMarker.builder()
                .toolCallId(toolCallId)
                .toolName(toolCall.getName())
                .sanitizedInput(sanitizedInput)
                .build());
        return checkpointRunningAtPromptBuild(context, EXECUTION_WINDOW_REASON);
    }

    /**
     * When History has a matching Tool Result, close the execution window without
     * replaying. A marker without a result becomes an Interrupted Tool Call.
     */
    public List<AgentEvent> reconcileToolDurability(AgentContext context) {
        ToolExecutionMarker window = context.getExecutionWindow();
        if (window == null || StringUtils.isBlank(window.getToolCallId())) {
            return List.of();
        }
        if (hasMatchingToolResult(context, window.getToolCallId())) {
            context.setExecutionWindow(null);
            return checkpointRunningAtPromptBuild(context, "after_tool");
        }
        context.addInterruptedToolCall(window);
        context.setExecutionWindow(null);
        historyAppend.appendSystemNote(context,
                ControlUpdateTexts.renderInterruptedToolCall(window.getToolName(), window.getToolCallId()),
                ConversationHistoryInitializer.eventKey(context.getRunId(),
                        window.getToolCallId(), "interrupted_tool"));
        return checkpointRunningAtPromptBuild(context, "interrupted_tool");
    }

    private boolean hasMatchingToolResult(AgentContext context, String toolCallId) {
        ConversationHistory history = context.getConversationHistory();
        if (history == null) {
            return false;
        }
        String expected = ConversationHistoryInitializer.eventKey(
                context.getRunId(), toolCallId, "tool_result");
        for (ConversationHistoryEntry entry : history.entries()) {
            if (entry.stableType() == cn.lunalhx.ai.domain.agent.model.valobj.ConversationEntryType.TOOL_RESULT
                    && expected.equals(entry.eventKey())) {
                return true;
            }
        }
        return false;
    }

    /** Post-tool checkpoint: the snapshot already contains the sanitized
     *  result, history, memory and ledger. The next recovery node is always
     *  {@code prompt_build} and the running context's current node is never
     *  mutated by the save. */
    public List<AgentEvent> checkpointAfterTool(AgentContext context) {
        context.setExecutionWindow(null);
        return checkpointRunningAtPromptBuild(context, "after_tool");
    }

    /** Skill activation changes durable prompt authority and must survive before the next tool step. */
    public List<AgentEvent> checkpointAfterSkillActivation(AgentContext context) {
        return checkpointRunningAtPromptBuild(context, "after_skill_activation");
    }

    private List<AgentEvent> checkpointRunningAtPromptBuild(AgentContext context, String reason) {
        AgentCheckpoint checkpoint = saveCheckpoint(context, AgentNodeNames.PROMPT_BUILD, reason);
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

    /** Terminal checkpoint so the final History anchor and Working Memory Overlay
     *  are durable with the terminal Run. */
    private void saveFinalCheckpoint(AgentContext context, String reason) {
        AgentCheckpoint checkpoint = saveCheckpoint(context, context.runtime().currentNode(), reason);
        context.setCheckpointVersion(checkpoint.getVersion());
    }

    // ================================================================
    // Internals
    // ================================================================

    private AgentCheckpoint saveCheckpoint(AgentContext context, String currentNode, String reason) {
        requireWritable(context);
        persistConversationHistory(context);
        AgentContextSnapshot snapshot = AgentContextSnapshot.from(context);
        return checkpointRepository.save(AgentCheckpoint.builder()
                .runId(context.identity().runId())
                .attemptId(context.getAttemptId())
                .currentNode(currentNode)
                .contextSnapshot(snapshot)
                .reason(reason)
                .build());
    }

    private void persistConversationHistory(AgentContext context) {
        requireWritable(context);
        String sessionId = context.getSessionId();
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        // Session Conversation History is shared across the task tree. Only the
        // root Run writes it; Delegate Runs must not shrink or fork the log.
        if (StringUtils.isNotBlank(context.getParentRunId())) {
            return;
        }
        ConversationHistory history = context.prompt().conversationHistory();
        if (history == null) {
            return;
        }
        historyRepository.save(sessionId, history);
    }

    private void requireWritable(AgentContext context) {
        leaseRepository.requireWritable(context.identity().runId(), context.getLeaseFence());
    }

    private void saveRun(AgentContext context, String currentNode, AgentRunStatus fallbackStatus) {
        requireWritable(context);
        AgentRuntimeState runtime = context.runtime();
        AgentIdentity id = context.identity();
        AgentBudgetState budget = context.budget();
        BudgetState budgetState = budget.budgetState();
        cn.lunalhx.ai.domain.agent.model.state.AgentRunDefinition def = context.runDefinition();

        AgentRunStatus status = resolveStatus(runtime, fallbackStatus);

        runRepository.save(AgentRun.builder()
                .schemaVersion(AgentRun.CURRENT_SCHEMA_VERSION)
                .runId(id.runId())
                .currentAttemptId(context.getAttemptId())
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
                .planRevision(def.planRevision())
                .planStateVersion(def.planStateVersion())
                .planBinding(def.planBinding())
                .planDeviation(context.getStopReason() == AgentStopReason.PLAN_DEVIATION
                        && context.getDecision() != null
                        && "plan_deviation".equals(context.getDecision().getType())
                        ? context.getDecision().getPlanDeviation() : null)
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
                        context.budget().budgetBlockedReason(), context.modelCall().contextBlockedReason()))
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
                || reason == AgentStopReason.RETRY_LIMIT_REACHED
                || reason == AgentStopReason.PLAN_DEVIATION) {
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
        if (reason == AgentStopReason.ABANDONED) {
            return AgentRunStatus.ABANDONED;
        }
        return fallback;
    }
}
