package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.model.valobj.WorkspaceResolutionException;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AgentEventFactory {

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
                .plan(context.getPlan() == null ? null : context.getPlan().toView())
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
                .permissionLevel(approval.getPermissionLevel() == null ? null : approval.getPermissionLevel().name())
                .riskReason(approval.getRiskReason())
                .operationPreview(approval.getOperationPreview())
                .expiresAt(approval.getExpiresAt())
                .build();
    }

    public AgentEvent highRiskApprovalRequired(AgentContext context, PendingApproval approval) {
        return AgentEvent.builder()
                .type(AgentEventType.HIGH_RISK_APPROVAL_REQUIRED)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(approval.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .step(context.getStep() + 1)
                .tool(approval.getTool())
                .input(approval.getInput())
                .approvalId(approval.getApprovalId())
                .permissionLevel(approval.getPermissionLevel() == null ? null : approval.getPermissionLevel().name())
                .riskReason(approval.getRiskReason())
                .operationPreview(approval.getOperationPreview())
                .diff(approval.getDiff())
                .expiresAt(approval.getExpiresAt())
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
                        "transcriptArtifactId", StringUtils.defaultString(context.getContextTranscriptArtifactId()),
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
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .code("agent_error")
                .message("Agent 执行失败")
                .build();
    }

    public AgentEvent approvalNotFound(String approvalId) {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .approvalId(approvalId)
                .code("approval_not_found")
                .message("审批不存在或已过期")
                .build();
    }

    public AgentEvent checkpointNotFound(String runId) {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .runId(runId)
                .code("checkpoint_not_found")
                .message("未找到可恢复的 checkpoint")
                .build();
    }

    public AgentEvent runNotWaitingUserInput(String runId) {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .runId(runId)
                .code("run_not_waiting_user_input")
                .message("当前运行不在等待用户输入状态")
                .build();
    }

    public AgentEvent invalidUserInput(String runId) {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .runId(runId)
                .code("invalid_user_input")
                .message("CONTINUE 必须提供非空 message")
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
                .code("run_already_terminal")
                .message("当前运行已结束，不能再次恢复")
                .metadata(Map.of("status", run.getStatus().name()))
                .build();
    }
}
