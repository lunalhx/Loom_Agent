package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.service.context.ContextWindowManager;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerInitializer;
import cn.lunalhx.ai.domain.agent.service.ledger.ControlUpdateTexts;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.types.error.ErrorCode;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ModelCallNode extends AbstractAgentNode {

    private final ModelGateway modelGateway;
    private final AgentRuntimeProperties properties;
    private final ModelPromptFactory promptFactory;
    private final ModelCallBudgetCoordinator budgetCoordinator;
    private final ModelCallFailureClassifier failureClassifier;
    private final ModelCallExecutor executor;
    private final ContextRecoveryChain recoveryChain;
    private final ConversationLedgerAppendService ledgerAppendService;

    public ModelCallNode(ModelGateway modelGateway,
                         AgentRuntimeProperties properties,
                         TraceRecorder traceRecorder,
                         BudgetGuard budgetGuard,
                         ContextWindowManager contextWindowManager,
                         ConversationLedgerAppendService ledgerAppendService) {
        super(AgentNodeNames.MODEL_CALL, List.of("currentPrompt", "requestId", "conversationId"));
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");

        this.promptFactory = new ModelPromptFactory(
                Boolean.TRUE.equals(properties.getConversationLedger().getEnabled()));
        this.budgetCoordinator = new ModelCallBudgetCoordinator(budgetGuard, traceRecorder, promptFactory);
        this.failureClassifier = new ModelCallFailureClassifier();
        this.executor = new ModelCallExecutor(modelGateway, promptFactory, budgetCoordinator);
        this.recoveryChain = new ContextRecoveryChain(List.of(
                new ReactiveCompactStep(properties, contextWindowManager, modelGateway),
                new FallbackModelStep(properties, modelGateway, budgetCoordinator),
                new DeepSummaryStep(properties, contextWindowManager, modelGateway),
                new ExhaustedStep()
        ));
        this.ledgerAppendService = ledgerAppendService;
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        long deadlineEpochMs = System.currentTimeMillis() + properties.getStepTimeoutMs();
        String requestedModel = StringUtils.defaultIfBlank(
                context.getRecoveryModelOverride(),
                context.getCurrentModel());
        int requestedMaxTokens = 0;
        int escalatedMaxTokens = escalatedMaxTokens();

        appendBudgetSnapshotIfApplicable(context);
        appendTodoReminderIfTriggered(context);

        ModelCallResult result = executor.execute(context, requestedModel, requestedMaxTokens,
                deadlineEpochMs, escalatedMaxTokens);

        if (result.isSuccess()) {
            context.setModelOutput(result.chatResult().getContent());
            context.setCurrentModel(result.chatResult().getActualModel());
            context.setFallbackReason(StringUtils.defaultIfBlank(
                    result.chatResult().getFallbackReason(), context.getFallbackReason()));
            resetContextRecovery(context);
            if (ledgerAppendService != null && ledgerAppendService.isActive()) {
                String eventKey = ConversationLedgerInitializer.eventKey(
                        context.getRunId(), String.valueOf(Math.max(1, context.getStep())), "assistant");
                ledgerAppendService.appendAssistant(
                        context, result.chatResult().getContent(), eventKey);
            }
            return NodeResult.next(AgentNodeNames.DECISION, List.of());
        }

        if (result.isBudgetBlocked()) {
            budgetCoordinator.blockForBudget(context, result.budgetCheck());
            return NodeResult.next(AgentNodeNames.FAIL, List.of());
        }

        if (result.isTruncationExhausted()) {
            fail(context, AgentStopReason.MODEL_ERROR,
                    ModelErrorCode.MODEL_DECISION_TRUNCATED.code(),
                    ModelErrorCode.MODEL_DECISION_TRUNCATED.defaultMessage());
            budgetCoordinator.traceRecovery(context, "model_recovery_exhausted", AgentNodeNames.MODEL_CALL,
                    Map.of("purpose", ModelCallPurpose.CONTROL_JSON.name(),
                            "finishReason", StringUtils.defaultString(result.chatResult().getFinishReason())));
            return NodeResult.next(AgentNodeNames.FAIL, List.of());
        }

        Throwable error = result.error();
        ModelCallFailureClassifier.Category category = failureClassifier.classify(error);

        switch (category) {
            case BUDGET_EXCEEDED:
                context.setBudgetBlockedReason("fallback model exceeds remaining budget");
                fail(context, AgentStopReason.BUDGET_EXCEEDED,
                        ModelErrorCode.BUDGET_EXCEEDED.code(), ModelErrorCode.BUDGET_EXCEEDED.defaultMessage());
                return NodeResult.next(AgentNodeNames.FAIL, List.of());

            case TIMEOUT:
                fail(context, AgentStopReason.TIMEOUT,
                        ModelErrorCode.MODEL_CALL_TIMEOUT.code(), ModelErrorCode.MODEL_CALL_TIMEOUT.defaultMessage());
                return NodeResult.next(AgentNodeNames.FAIL, List.of());

            case CONTEXT_OVERFLOW:
                return recoveryChain.execute(context,
                        StringUtils.defaultIfBlank(result.attemptedModel(), context.getRecoveryModelOverride()),
                        result.requestedMaxTokens(), deadlineEpochMs);

            case GATEWAY_ERROR: {
                ModelGatewayException gatewayException = failureClassifier.modelGatewayException(error);
                ErrorCode errorCode = gatewayException.getErrorCode() == null
                        ? ModelErrorCode.MODEL_ERROR : gatewayException.getErrorCode();
                fail(context, AgentStopReason.MODEL_ERROR, errorCode.code(),
                        StringUtils.defaultIfBlank(gatewayException.getMessage(), errorCode.defaultMessage()));
                return NodeResult.next(AgentNodeNames.FAIL, List.of());
            }

            default:
                fail(context, AgentStopReason.MODEL_ERROR, "model_error", "模型决策失败");
                return NodeResult.next(AgentNodeNames.FAIL, List.of());
        }
    }

    private int escalatedMaxTokens() {
        Integer value = properties.getModelRecovery() == null
                ? null : properties.getModelRecovery().getEscalatedMaxTokens();
        return value == null || value <= 0 ? 8192 : value;
    }

    private void resetContextRecovery(AgentContext context) {
        context.setContextRecoveryStage(ContextRecoveryStage.NONE);
        context.setReactiveCompactAttempts(0);
        context.setRecoveryModelOverride(null);
        context.setContextTranscriptArtifactId(null);
        context.setContextBlockedReason(null);
    }

    private void appendBudgetSnapshotIfApplicable(AgentContext context) {
        if (ledgerAppendService == null || !ledgerAppendService.isActive()) {
            return;
        }
        String text = ControlUpdateTexts.renderBudgetSnapshot(context);
        if (text.isEmpty()) {
            return;
        }
        String eventKey = ConversationLedgerInitializer.eventKey(
                context.getRunId(), String.valueOf(Math.max(1, context.getStep())), "budget");
        ledgerAppendService.appendControlUpdate(context, text, eventKey);
    }

    private void appendTodoReminderIfTriggered(AgentContext context) {
        if (ledgerAppendService == null || !ledgerAppendService.isActive()) {
            return;
        }
        if (context.getPlan() == null || context.getPlan().getRoundsSinceUpdate() < 3) {
            return;
        }
        String text = ControlUpdateTexts.renderTodoReminder();
        String eventKey = ConversationLedgerInitializer.eventKey(
                context.getRunId(), String.valueOf(Math.max(1, context.getStep())), "todo_reminder");
        ledgerAppendService.appendControlUpdate(context, text, eventKey);
    }
}
