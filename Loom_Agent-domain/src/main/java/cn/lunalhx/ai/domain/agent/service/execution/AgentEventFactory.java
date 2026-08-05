package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentErrorCode;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.model.valobj.WorkspaceResolutionException;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AgentEventFactory {

    public AgentEvent runStarted(AgentContext context) {
        return AgentEvent.builder()
                .type(AgentEventType.RUN_STARTED)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .checkpointVersion(context.getCheckpointVersion())
                .toolSteps(context.getToolSteps())
                .modelAttempts(context.getModelAttempts())
                .lastTool(context.getLastTool())
                .maxToolSteps(context.getMaxSteps())
                .maxAttempts(context.getMaxAttempts())
                .build();
    }

    public AgentEvent meta(AgentContext context) {
        return AgentEvent.builder()
                .type(AgentEventType.META)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .toolSteps(context.getToolSteps())
                .modelAttempts(context.getModelAttempts())
                .lastTool(context.getLastTool())
                .maxToolSteps(context.getMaxSteps())
                .maxAttempts(context.getMaxAttempts())
                .build();
    }

    public AgentEvent answer(AgentContext context, String answer) {
        return AgentEvent.builder()
                .type(AgentEventType.ANSWER)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .toolSteps(context.getToolSteps())
                .modelAttempts(context.getModelAttempts())
                .lastTool(context.getLastTool())
                .answer(answer)
                .build();
    }

    public AgentEvent done(AgentContext context, AgentStopReason stopReason) {
        return AgentEvent.builder()
                .type(AgentEventType.DONE)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .toolSteps(context.getToolSteps())
                .modelAttempts(context.getModelAttempts())
                .lastTool(context.getLastTool())
                .stopReason(stopReason)
                .answer(context.getFinalAnswer())
                .checkpointVersion(context.getCheckpointVersion())
                .build();
    }

    public AgentEvent error(AgentContext context) {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .runId(context == null ? null : context.getRunId())
                .requestId(context == null ? null : context.getRequestId())
                .conversationId(context == null ? null : context.getConversationId())
                .workspace(context == null ? null : context.getWorkspaceDisplayName())
                .parentRunId(context == null ? null : context.getParentRunId())
                .toolSteps(context == null ? null : context.getToolSteps())
                .modelAttempts(context == null ? null : context.getModelAttempts())
                .lastTool(context == null ? null : context.getLastTool())
                .checkpointVersion(context == null ? null : context.getCheckpointVersion())
                .recoverable(context != null && context.getCheckpointVersion() != null)
                .code(context == null ? AgentErrorCode.AGENT_ERROR.code() : context.getErrorCode())
                .message(context == null ? AgentErrorCode.AGENT_ERROR.defaultMessage() : context.getErrorMessage())
                .build();
    }

    public AgentEvent nodeStarted(AgentContext context, AgentNode node) {
        return AgentEvent.builder()
                .type(AgentEventType.NODE_START)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .node(node.name())
                .nodeInputs(node.inputKeys())
                .build();
    }

    public AgentEvent userInputRequired(AgentContext context) {
        return AgentEvent.builder()
                .type(AgentEventType.USER_INPUT_REQUIRED)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .code(ModelErrorCode.CONTEXT_OVERFLOW.code())
                .message("自动上下文恢复已耗尽。请补充更聚焦的指令后继续，或终止本次运行。")
                .metadata(Map.of(
                        "allowedActions", List.of("CONTINUE", "ABORT"),
                        "recoveryStage", ContextRecoveryStage.WAITING_USER_INPUT.name(),
                        "blockedReason", StringUtils.defaultString(context.getContextBlockedReason())))
                .build();
    }

    public AgentEvent workspaceError(WorkspaceResolutionException e) {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .requestId(UUID.randomUUID().toString())
                .stopReason(AgentStopReason.MODEL_ERROR)
                .code(e.getCode())
                .message(e.getMessage())
                .build();
    }

    public AgentEvent agentError() {
        return agentError(null);
    }

    public AgentEvent agentError(AgentContext context) {
        String code = context != null && StringUtils.isNotBlank(context.getErrorCode())
                ? context.getErrorCode() : AgentErrorCode.AGENT_ERROR.code();
        String message = context != null && StringUtils.isNotBlank(context.getErrorMessage())
                ? context.getErrorMessage() : AgentErrorCode.AGENT_ERROR.defaultMessage();
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .runId(context == null ? null : context.getRunId())
                .requestId(context == null ? null : context.getRequestId())
                .conversationId(context == null ? null : context.getConversationId())
                .workspace(context == null ? null : context.getWorkspaceDisplayName())
                .parentRunId(context == null ? null : context.getParentRunId())
                .toolSteps(context == null ? null : context.getToolSteps())
                .modelAttempts(context == null ? null : context.getModelAttempts())
                .lastTool(context == null ? null : context.getLastTool())
                .checkpointVersion(context == null ? null : context.getCheckpointVersion())
                .recoverable(context != null && context.getCheckpointVersion() != null)
                .code(code)
                .message(message)
                .build();
    }

    public AgentEvent checkpointNotFound(String runId) {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .runId(runId)
                .recoverable(false)
                .code(AgentErrorCode.CHECKPOINT_NOT_FOUND.code())
                .message(AgentErrorCode.CHECKPOINT_NOT_FOUND.defaultMessage())
                .build();
    }

    public AgentEvent conversationBusy(String conversationId, String runId, String requestId,
                                       String holderRunId, String operation, Instant startedAt) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (holderRunId != null) {
            metadata.put("holderRunId", holderRunId);
        }
        if (operation != null) {
            metadata.put("operation", operation);
        }
        if (startedAt != null) {
            metadata.put("startedAt", startedAt.toString());
        }
        metadata.put("retryable", true);
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .runId(runId)
                .requestId(requestId)
                .conversationId(conversationId)
                .code(AgentErrorCode.CONVERSATION_BUSY.code())
                .message(AgentErrorCode.CONVERSATION_BUSY.defaultMessage())
                .metadata(metadata)
                .build();
    }
}
