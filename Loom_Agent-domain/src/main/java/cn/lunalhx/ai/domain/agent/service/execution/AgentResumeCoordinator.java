package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.ApprovalDecisionResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalRecordState;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.model.valobj.UserInputAction;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.agent.service.context.AgentContextFactory;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
import cn.lunalhx.ai.domain.agent.service.ledger.ControlUpdateTexts;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AgentResumeCoordinator {

    private static final int MIN_SUPPORTED_SNAPSHOT_VERSION = 9;

    private final ApprovalStore approvalStore;
    private final AgentCheckpointRepository checkpointRepository;
    private final AgentRunRepository runRepository;
    private final AgentContextFactory contextFactory;
    private final AgentEventFactory eventFactory;
    private final ConversationHistoryAppendService ledgerAppendService;

    public AgentResumeCoordinator(ApprovalStore approvalStore,
                                  AgentCheckpointRepository checkpointRepository,
                                  AgentRunRepository runRepository,
                                  AgentContextFactory contextFactory,
                                  AgentEventFactory eventFactory,
                                  ConversationHistoryAppendService ledgerAppendService) {
        this.approvalStore = approvalStore;
        this.checkpointRepository = checkpointRepository;
        this.runRepository = runRepository;
        this.contextFactory = contextFactory;
        this.eventFactory = eventFactory;
        this.ledgerAppendService = ledgerAppendService;
    }

    public AgentResumePlan prepareApprovalResume(
            String approvalId, ApprovalDecision decision, String reason,
            String reasonCode, List<String> allowedAlternatives) {
        ApprovalDecisionResult claim = approvalStore.decide(approvalId, decision, reason);
        if (claim.outcome() == ApprovalDecisionResult.Outcome.NOT_FOUND) {
            return AgentResumePlan.complete(List.of(eventFactory.approvalNotFound(approvalId)));
        }
        PendingApproval approval = claim.approval();
        boolean recoverDecidedApproval =
                claim.outcome() == ApprovalDecisionResult.Outcome.IDEMPOTENT
                        && approval != null
                        && approval.getState() == ApprovalRecordState.DECIDED;
        if (claim.outcome() == ApprovalDecisionResult.Outcome.IDEMPOTENT
                && !recoverDecidedApproval) {
            return AgentResumePlan.complete(
                    List.of(eventFactory.approvalAlreadyDecided(approval)));
        }
        if (claim.outcome() == ApprovalDecisionResult.Outcome.CONFLICT) {
            return AgentResumePlan.complete(
                    List.of(eventFactory.approvalDecisionConflict(approval)));
        }

        ApprovalDecision effectiveDecision = recoverDecidedApproval
                ? approval.getDecision() : decision;
        String effectiveReason = recoverDecidedApproval
                ? approval.getDecisionReason() : reason;
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

        if (effectiveDecision == ApprovalDecision.APPROVE) {
            context.approval().approve(approval.getTool(), null);
            appendApprovalToLedger(context, "approved",
                    approval.getTool(), effectiveReason);
            return durableApprovalResume(
                    approvalId, context, AgentNodeNames.TOOL_DISPATCH, events);
        }

        context.approval().reject();

        appendApprovalToLedger(context, "rejected",
                approval.getTool(), effectiveReason);

        ToolResult rejection = ToolResult.failure(
                "approval_rejected",
                StringUtils.defaultIfBlank(effectiveReason, "用户拒绝执行该写操作"),
                0L);
        rejection.setDetails(Map.of(
                "reasonCode", StringUtils.defaultIfBlank(
                        reasonCode, "approval_rejected"),
                "allowedAlternatives",
                allowedAlternatives == null ? List.of() : allowedAlternatives));
        context.setToolResult(rejection);
        return durableApprovalResume(
                approvalId, context, AgentNodeNames.OBSERVATION, events);
    }

    private AgentResumePlan durableApprovalResume(
            String approvalId,
            AgentContext context,
            String nextNode,
            List<AgentEvent> events) {
        AgentCheckpoint checkpoint = checkpointRepository.save(
                AgentCheckpoint.builder()
                        .runId(context.getRunId())
                        .currentNode(nextNode)
                        .contextSnapshot(AgentContextSnapshot.from(context))
                        .reason("approval_decided:" + approvalId)
                        .build());
        context.setCheckpointVersion(checkpoint.getVersion());
        approvalStore.markResumed(approvalId);
        return AgentResumePlan.continueAt(context, nextNode, events);
    }

    public AgentResumePlan prepareRunResume(String runId) {
        AgentRun run = runRepository.find(runId).orElse(null);
        if (run != null && run.getStatus() != null && run.getStatus().terminal()) {
            return AgentResumePlan.complete(List.of(eventFactory.runAlreadyTerminal(run)));
        }

        AgentCheckpoint checkpoint = checkpointRepository.latest(runId).orElse(null);
        if (checkpoint == null || checkpoint.getContextSnapshot() == null) {
            return AgentResumePlan.complete(List.of(eventFactory.checkpointNotFound(runId)));
        }

        AgentContextSnapshot snapshot = checkpoint.getContextSnapshot();
        if (snapshot.getSchemaVersion() < MIN_SUPPORTED_SNAPSHOT_VERSION) {
            return AgentResumePlan.complete(List.of(eventFactory.checkpointNotFound(runId)));
        }

        AgentContext context = snapshot.restore();
        contextFactory.prepareCheckpointResume(context,
                context.getResolvedWorkspace() == null ? null : context.getResolvedWorkspace().toString(),
                checkpoint.getVersion());

        List<AgentEvent> events = new ArrayList<>();
        events.add(eventFactory.resumeStarted(context));

        if (AgentNodeNames.APPROVAL_GATE.equals(checkpoint.getCurrentNode())
                && StringUtils.isNotBlank(context.approval().pendingApprovalId())) {
            PendingApproval approval = approvalStore.find(context.approval().pendingApprovalId()).orElse(null);
            if (approval != null) {
                events.add(eventFactory.approvalRequired(context, approval));
                return AgentResumePlan.complete(events);
            }
            String expiredId = context.approval().pendingApprovalId();
            context.approval().expire(expiredId);
            context.setToolResult(ToolResult.failure(
                    "policy_denied",
                    "审批已过期或不可用，写操作未执行",
                    0L));
            appendApprovalExpiredToLedger(context, expiredId);
            return AgentResumePlan.continueAt(context, AgentNodeNames.OBSERVATION, events);
        }

        if (context.recovery().contextRecoveryStage() == ContextRecoveryStage.WAITING_USER_INPUT) {
            events.add(eventFactory.userInputRequired(context));
            return AgentResumePlan.complete(events);
        }

        String currentNode = StringUtils.defaultIfBlank(checkpoint.getCurrentNode(), AgentNodeNames.PROMPT_BUILD);
        return AgentResumePlan.continueAt(context, currentNode, events);
    }

    public AgentResumePlan prepareUserInputResume(String runId, UserInputAction action, String message) {
        AgentCheckpoint checkpoint = checkpointRepository.latest(runId).orElse(null);
        if (checkpoint == null || checkpoint.getContextSnapshot() == null) {
            return AgentResumePlan.complete(List.of(eventFactory.checkpointNotFound(runId)));
        }

        AgentContextSnapshot snapshot = checkpoint.getContextSnapshot();
        if (snapshot.getSchemaVersion() < MIN_SUPPORTED_SNAPSHOT_VERSION) {
            return AgentResumePlan.complete(List.of(eventFactory.checkpointNotFound(runId)));
        }

        AgentContext context = snapshot.restore();
        if (context.recovery().contextRecoveryStage() != ContextRecoveryStage.WAITING_USER_INPUT) {
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
            context.recovery().reset();
            context.runtime().fail(AgentStopReason.CONTEXT_OVERFLOW, ModelErrorCode.CONTEXT_OVERFLOW.code(),
                    "用户在上下文恢复等待阶段终止了本次运行");
            events.add(eventFactory.agentError(context));
            return AgentResumePlan.complete(events);
        }

        appendUserInputToLedger(context, StringUtils.trim(message));
        context.recovery().reset();
        context.runtime().clearOutcomeForContinuation();
        return AgentResumePlan.continueAt(context, AgentNodeNames.PROMPT_BUILD, events);
    }

    private void appendUserInputToLedger(AgentContext context, String message) {
        if (ledgerAppendService == null) {
            return;
        }
        String text = ControlUpdateTexts.renderUserInput(message);
        String eventKey = ConversationHistoryInitializer.eventKey(
                context.getRunId(), String.valueOf(Math.max(1, context.getToolSteps())), "user_input");
        ledgerAppendService.appendUserInput(context, text, eventKey);
    }

    private void appendApprovalToLedger(AgentContext context, String decision,
                                         String toolName, String reason) {
        if (ledgerAppendService == null) {
            return;
        }
        String text = ControlUpdateTexts.renderApprovalDecision(decision, toolName, reason);
        String eventKey = ConversationHistoryInitializer.eventKey(
                context.getRunId(), String.valueOf(Math.max(1, context.getToolSteps())),
                "approval_" + decision);
        ledgerAppendService.appendControlUpdate(context, text, eventKey);
    }

    private void appendApprovalExpiredToLedger(AgentContext context, String approvalId) {
        if (ledgerAppendService == null) {
            return;
        }
        String text = ControlUpdateTexts.renderApprovalExpired(approvalId);
        String eventKey = ConversationHistoryInitializer.eventKey(
                context.getRunId(), String.valueOf(Math.max(1, context.getToolSteps())),
                "approval_expired");
        ledgerAppendService.appendControlUpdate(context, text, eventKey);
    }
}