package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
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

    public AgentEvent resumeStarted(AgentContext context) {
        return AgentEvent.builder()
                .type(AgentEventType.RESUME_STARTED)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .checkpointVersion(context.getCheckpointVersion())
                .build();
    }

    public AgentEvent approvalRequired(AgentContext context, PendingApproval approval) {
        return AgentEvent.builder()
                .type(AgentEventType.APPROVAL_REQUIRED)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(approval.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .step(context.getStep() + 1)
                .tool(approval.getTool())
                .input(approval.getInput())
                .approvalId(approval.getApprovalId())
                .riskReason(approval.getRiskReason())
                .operationPreview(approval.getOperationPreview())
                .metadata(approval.getMetadata())
                .expiresAt(approval.getExpiresAt())
                .build();
    }

    public AgentEvent highRiskApprovalRequired(AgentContext context, PendingApproval approval) {
        return approvalRequired(context, approval);
    }

    public AgentEvent pausedForApproval(AgentContext context) {
        return AgentEvent.builder()
                .type(AgentEventType.PAUSED_FOR_APPROVAL)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .approvalId(context.getPendingApprovalId())
                .checkpointVersion(context.getCheckpointVersion())
                .recoverable(true)
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
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .runId(context == null ? null : context.getRunId())
                .requestId(context == null ? null : context.getRequestId())
                .conversationId(context == null ? null : context.getConversationId())
                .checkpointVersion(context == null ? null : context.getCheckpointVersion())
                .recoverable(context != null && context.getCheckpointVersion() != null)
                .code(AgentErrorCode.AGENT_ERROR.code())
                .message(AgentErrorCode.AGENT_ERROR.defaultMessage())
                .build();
    }

    public AgentEvent approvalNotFound(String approvalId) {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .approvalId(approvalId)
                .code(AgentErrorCode.APPROVAL_NOT_FOUND.code())
                .message(AgentErrorCode.APPROVAL_NOT_FOUND.defaultMessage())
                .build();
    }

    public AgentEvent approvalAlreadyDecided(PendingApproval approval) {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .approvalId(approval == null ? null : approval.getApprovalId())
                .runId(approval == null ? null : approval.getRunId())
                .recoverable(true)
                .code("approval_already_decided")
                .message("该审批已按相同决定处理；不会重复执行工具")
                .build();
    }

    public AgentEvent approvalDecisionConflict(PendingApproval approval) {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .approvalId(approval == null ? null : approval.getApprovalId())
                .runId(approval == null ? null : approval.getRunId())
                .recoverable(false)
                .code(AgentErrorCode.APPROVAL_DECISION_CONFLICT.code())
                .message(AgentErrorCode.APPROVAL_DECISION_CONFLICT.defaultMessage())
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

    public AgentEvent runNotWaitingUserInput(String runId) {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .runId(runId)
                .code(AgentErrorCode.RUN_NOT_WAITING_USER_INPUT.code())
                .message(AgentErrorCode.RUN_NOT_WAITING_USER_INPUT.defaultMessage())
                .build();
    }

    public AgentEvent invalidUserInput(String runId) {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .runId(runId)
                .code(AgentErrorCode.INVALID_USER_INPUT.code())
                .message(AgentErrorCode.INVALID_USER_INPUT.defaultMessage())
                .build();
    }

    public AgentEvent runAlreadyTerminal(AgentRun run) {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .runId(run.getRunId())
                .requestId(run.getRequestId())
                .conversationId(run.getConversationId())
                .workspace(run.getWorkspace())
                .parentRunId(run.getParentRunId())
                .recoverable(false)
                .code(AgentErrorCode.RUN_ALREADY_TERMINAL.code())
                .message(AgentErrorCode.RUN_ALREADY_TERMINAL.defaultMessage())
                .metadata(Map.of("status", run.getStatus().name()))
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
