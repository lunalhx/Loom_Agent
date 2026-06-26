package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.model.valobj.UserInputAction;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public final class AgentResumeCoordinator {

    private final ApprovalStore approvalStore;
    private final AgentCheckpointRepository checkpointRepository;
    private final AgentContextFactory contextFactory;
    private final AgentEventFactory eventFactory;

    public AgentResumeCoordinator(ApprovalStore approvalStore,
                                  AgentCheckpointRepository checkpointRepository,
                                  AgentContextFactory contextFactory,
                                  AgentEventFactory eventFactory) {
        this.approvalStore = approvalStore;
        this.checkpointRepository = checkpointRepository;
        this.contextFactory = contextFactory;
        this.eventFactory = eventFactory;
    }

    public AgentResumePlan prepareApprovalResume(String approvalId, ApprovalDecision decision, String reason) {
        PendingApproval approval = approvalStore.consume(approvalId).orElse(null);
        if (approval == null) {
            return AgentResumePlan.complete(List.of(eventFactory.approvalNotFound(approvalId)));
        }

        AgentContext context = approval.getContext();
        if (StringUtils.isNotBlank(approval.getRunId())) {
            AgentCheckpoint checkpoint = checkpointRepository.latest(approval.getRunId()).orElse(null);
            if (checkpoint != null && checkpoint.getContextSnapshot() != null) {
                context = checkpoint.getContextSnapshot().restore();
            }
        }
        contextFactory.prepareApprovalResume(context, approval);

        List<AgentEvent> events = new ArrayList<>();
        events.add(eventFactory.resumeStarted(context));

        if (decision == ApprovalDecision.APPROVE) {
            return AgentResumePlan.continueAt(context, AgentNodeNames.TOOL_DISPATCH, events);
        }

        context.setStep(context.getStep() + 1);
        context.setToolResult(ToolResult.failure(
                "approval_rejected",
                StringUtils.defaultIfBlank(reason, "用户拒绝执行该写操作"),
                0L));
        context.getDynamicText().appendAssistantAction(context.getStep(), AgentNodeNames.APPROVAL_GATE, context.getDecision());
        return AgentResumePlan.continueAt(context, AgentNodeNames.OBSERVATION, events);
    }

    public AgentResumePlan prepareRunResume(String runId) {
        AgentCheckpoint checkpoint = checkpointRepository.latest(runId).orElse(null);
        if (checkpoint == null || checkpoint.getContextSnapshot() == null) {
            return AgentResumePlan.complete(List.of(eventFactory.checkpointNotFound(runId)));
        }

        AgentContext context = checkpoint.getContextSnapshot().restore();
        contextFactory.prepareCheckpointResume(context,
                context.getResolvedWorkspace() == null ? null : context.getResolvedWorkspace().toString(),
                checkpoint.getVersion());

        List<AgentEvent> events = new ArrayList<>();
        events.add(eventFactory.resumeStarted(context));

        if (AgentNodeNames.APPROVAL_GATE.equals(checkpoint.getCurrentNode())
                && StringUtils.isNotBlank(context.getPendingApprovalId())) {
            PendingApproval approval = approvalStore.find(context.getPendingApprovalId()).orElse(null);
            if (approval != null) {
                events.add(eventFactory.approvalRequired(context, approval));
                return AgentResumePlan.complete(events);
            }
            String expiredId = context.getPendingApprovalId();
            context.setPendingApprovalId(null);
            context.setApprovalExpired(true);
            context.setExpiredApprovalId(expiredId);
            context.setStep(context.getStep() + 1);
            context.setToolResult(ToolResult.failure(
                    "policy_denied",
                    "审批已过期或不可用，写操作未执行",
                    0L));
            context.getDynamicText().appendAssistantAction(context.getStep(), AgentNodeNames.APPROVAL_GATE, context.getDecision());
            return AgentResumePlan.continueAt(context, AgentNodeNames.OBSERVATION, events);
        }

        if (AgentNodeNames.USER_INPUT_GATE.equals(checkpoint.getCurrentNode())
                || context.getContextRecoveryStage() == ContextRecoveryStage.WAITING_USER_INPUT) {
            events.add(eventFactory.userInputRequired(context));
            return AgentResumePlan.complete(events);
        }

        String currentNode = StringUtils.defaultIfBlank(checkpoint.getCurrentNode(), AgentNodeNames.RENDER_PROMPT);
        if (context.isUnsafeResumeRequired() || requiresResumeReplan(currentNode)) {
            context.setUnsafeResumeRequired(true);
            currentNode = AgentNodeNames.REPLAN_GUARD;
        }
        return AgentResumePlan.continueAt(context, currentNode, events);
    }

    public AgentResumePlan prepareUserInputResume(String runId, UserInputAction action, String message) {
        AgentCheckpoint checkpoint = checkpointRepository.latest(runId).orElse(null);
        if (checkpoint == null || checkpoint.getContextSnapshot() == null) {
            return AgentResumePlan.complete(List.of(eventFactory.checkpointNotFound(runId)));
        }

        AgentContext context = checkpoint.getContextSnapshot().restore();
        if (!AgentNodeNames.USER_INPUT_GATE.equals(checkpoint.getCurrentNode())
                && context.getContextRecoveryStage() != ContextRecoveryStage.WAITING_USER_INPUT) {
            return AgentResumePlan.complete(List.of(eventFactory.runNotWaitingUserInput(runId)));
        }

        if (action == null || (action == UserInputAction.CONTINUE && StringUtils.isBlank(message))) {
            return AgentResumePlan.complete(List.of(eventFactory.invalidUserInput(runId)));
        }

        contextFactory.prepareCheckpointResume(context,
                context.getResolvedWorkspace() == null ? null : context.getResolvedWorkspace().toString(),
                checkpoint.getVersion());

        List<AgentEvent> events = new ArrayList<>();
        events.add(eventFactory.resumeStarted(context));

        if (action == UserInputAction.ABORT) {
            context.setContextRecoveryStage(ContextRecoveryStage.NONE);
            context.setStopReason(AgentStopReason.CONTEXT_OVERFLOW);
            context.setErrorCode(ModelErrorCode.CONTEXT_OVERFLOW.code());
            context.setErrorMessage("用户在上下文恢复等待阶段终止了本次运行");
            return AgentResumePlan.continueAt(context, AgentNodeNames.FAIL, events);
        }

        context.getDynamicText().appendUserInput(context.getStep(), StringUtils.trim(message));
        context.setContextRecoveryStage(ContextRecoveryStage.NONE);
        context.setReactiveCompactAttempts(0);
        context.setRecoveryModelOverride(null);
        context.setContextTranscriptArtifactId(null);
        context.setContextBlockedReason(null);
        context.setFallbackReason(null);
        context.setStopReason(null);
        context.setErrorCode(null);
        context.setErrorMessage(null);
        return AgentResumePlan.continueAt(context, AgentNodeNames.RENDER_PROMPT, events);
    }

    private boolean requiresResumeReplan(String currentNode) {
        return AgentNodeNames.APPROVAL_GATE.equals(currentNode)
                || AgentNodeNames.TOOL_DISPATCH.equals(currentNode);
    }
}
