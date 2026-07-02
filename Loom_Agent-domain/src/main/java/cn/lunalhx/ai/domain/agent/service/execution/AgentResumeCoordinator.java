package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.state.AgentRuntimeState;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.model.valobj.UserInputAction;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.agent.service.context.AgentContextFactory;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AgentResumeCoordinator {

    private final ApprovalStore approvalStore;
    private final AgentCheckpointRepository checkpointRepository;
    private final AgentRunRepository runRepository;
    private final AgentContextFactory contextFactory;
    private final AgentEventFactory eventFactory;

    public AgentResumeCoordinator(ApprovalStore approvalStore,
                                  AgentCheckpointRepository checkpointRepository,
                                  AgentRunRepository runRepository,
                                  AgentContextFactory contextFactory,
                                  AgentEventFactory eventFactory) {
        this.approvalStore = approvalStore;
        this.checkpointRepository = checkpointRepository;
        this.runRepository = runRepository;
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
            context.approval().approve(approval.getTool(), approval.getPolicyFingerprint());
            if (isSkillActivation(approval)) {
                List<String> skillNames = extractSkillNames(approval);
                context.setApprovedSkillNames(skillNames);
                return AgentResumePlan.continueAt(context, AgentNodeNames.SKILL_BOOTSTRAP, events);
            }
            return AgentResumePlan.continueAt(context, AgentNodeNames.TOOL_DISPATCH, events);
        }

        // REJECT
        context.approval().reject();

        if (isSkillActivation(approval)) {
            List<String> rejectedNames = extractSkillNames(approval);
            context.setRejectedSkillNames(rejectedNames);
            if (context.getRequestedSkills() != null) {
                List<String> filtered = new ArrayList<>(context.getRequestedSkills());
                filtered.removeAll(rejectedNames);
                context.setRequestedSkills(filtered);
            }
            context.getDynamicText().appendAssistantAction(context.getStep(), AgentNodeNames.SKILL_BOOTSTRAP, context.getDecision());
            context.runtime().advanceStep();
            return AgentResumePlan.continueAt(context, AgentNodeNames.START, events);
        }

        context.runtime().advanceStep();
        context.setToolResult(ToolResult.failure(
                "approval_rejected",
                StringUtils.defaultIfBlank(reason, "用户拒绝执行该写操作"),
                0L));
        context.getDynamicText().appendAssistantAction(context.getStep(), AgentNodeNames.APPROVAL_GATE, context.getDecision());
        return AgentResumePlan.continueAt(context, AgentNodeNames.OBSERVATION, events);
    }

    public AgentResumePlan prepareRunResume(String runId) {
        AgentRun run = runRepository.find(runId).orElse(null);
        if (run != null && isTerminalStatus(run.getStatus())) {
            return AgentResumePlan.complete(List.of(eventFactory.runAlreadyTerminal(run)));
        }

        AgentCheckpoint checkpoint = checkpointRepository.latest(runId).orElse(null);
        if (checkpoint == null || checkpoint.getContextSnapshot() == null) {
            return AgentResumePlan.complete(List.of(eventFactory.checkpointNotFound(runId)));
        }

        AgentContextSnapshot snapshot = checkpoint.getContextSnapshot();
        if (snapshot.getSchemaVersion() != 2) {
            return AgentResumePlan.complete(List.of(eventFactory.checkpointNotFound(runId)));
        }

        AgentContext context = snapshot.restore();
        contextFactory.prepareCheckpointResume(context,
                context.getResolvedWorkspace() == null ? null : context.getResolvedWorkspace().toString(),
                checkpoint.getVersion());

        List<AgentEvent> events = new ArrayList<>();
        events.add(eventFactory.resumeStarted(context));

        AgentRuntimeState runtime = context.runtime();

        if (AgentNodeNames.APPROVAL_GATE.equals(checkpoint.getCurrentNode())
                && StringUtils.isNotBlank(context.approval().pendingApprovalId())) {
            PendingApproval approval = approvalStore.find(context.approval().pendingApprovalId()).orElse(null);
            if (approval != null) {
                if (approval.getPermissionLevel() == ToolPermissionLevel.HIGH_RISK_CONFIRM) {
                    events.add(eventFactory.highRiskApprovalRequired(context, approval));
                } else {
                    events.add(eventFactory.approvalRequired(context, approval));
                }
                return AgentResumePlan.complete(events);
            }
            String expiredId = context.approval().pendingApprovalId();
            context.approval().expire(expiredId);
            runtime.advanceStep();
            context.setToolResult(ToolResult.failure(
                    "policy_denied",
                    "审批已过期或不可用，写操作未执行",
                    0L));
            context.getDynamicText().appendAssistantAction(runtime.step(), AgentNodeNames.APPROVAL_GATE, context.getDecision());
            return AgentResumePlan.continueAt(context, AgentNodeNames.OBSERVATION, events);
        }

        if (AgentNodeNames.USER_INPUT_GATE.equals(checkpoint.getCurrentNode())
                || context.recovery().contextRecoveryStage() == ContextRecoveryStage.WAITING_USER_INPUT) {
            events.add(eventFactory.userInputRequired(context));
            return AgentResumePlan.complete(events);
        }

        String currentNode = StringUtils.defaultIfBlank(checkpoint.getCurrentNode(), AgentNodeNames.RENDER_PROMPT);
        if (context.approval().unsafeResumeRequired() || requiresResumeReplan(currentNode)) {
            context.approval().setUnsafeResumeRequired(true);
            currentNode = AgentNodeNames.REPLAN_GUARD;
        }
        return AgentResumePlan.continueAt(context, currentNode, events);
    }

    public AgentResumePlan prepareUserInputResume(String runId, UserInputAction action, String message) {
        AgentCheckpoint checkpoint = checkpointRepository.latest(runId).orElse(null);
        if (checkpoint == null || checkpoint.getContextSnapshot() == null) {
            return AgentResumePlan.complete(List.of(eventFactory.checkpointNotFound(runId)));
        }

        AgentContextSnapshot snapshot = checkpoint.getContextSnapshot();
        if (snapshot.getSchemaVersion() != 2) {
            return AgentResumePlan.complete(List.of(eventFactory.checkpointNotFound(runId)));
        }

        AgentContext context = snapshot.restore();
        if (!AgentNodeNames.USER_INPUT_GATE.equals(checkpoint.getCurrentNode())
                && context.recovery().contextRecoveryStage() != ContextRecoveryStage.WAITING_USER_INPUT) {
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

        AgentRuntimeState runtime = context.runtime();

        if (action == UserInputAction.ABORT) {
            context.recovery().reset();
            runtime.fail(AgentStopReason.CONTEXT_OVERFLOW, ModelErrorCode.CONTEXT_OVERFLOW.code(),
                    "用户在上下文恢复等待阶段终止了本次运行");
            return AgentResumePlan.continueAt(context, AgentNodeNames.FAIL, events);
        }

        context.getDynamicText().appendUserInput(runtime.step(), StringUtils.trim(message));
        context.recovery().reset();
        runtime.clearOutcomeForContinuation();
        return AgentResumePlan.continueAt(context, AgentNodeNames.RENDER_PROMPT, events);
    }

    private boolean requiresResumeReplan(String currentNode) {
        return AgentNodeNames.APPROVAL_GATE.equals(currentNode)
                || AgentNodeNames.TOOL_DISPATCH.equals(currentNode);
    }

    private boolean isTerminalStatus(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED
                || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.BUDGET_EXCEEDED;
    }

    private boolean isSkillActivation(PendingApproval approval) {
        if (approval.getMetadata() == null) {
            return false;
        }
        return "skill_activation".equals(approval.getMetadata().get("kind"));
    }

    @SuppressWarnings("unchecked")
    private List<String> extractSkillNames(PendingApproval approval) {
        List<String> names = new ArrayList<>();
        if (approval.getMetadata() == null) {
            return names;
        }
        Object skillsObj = approval.getMetadata().get("skills");
        if (skillsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m && m.get("name") instanceof String name) {
                    names.add(name);
                }
            }
        }
        return names;
    }
}
