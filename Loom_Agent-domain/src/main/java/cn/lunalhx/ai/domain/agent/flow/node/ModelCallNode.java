package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.flow.middleware.ModelCallContext;
import cn.lunalhx.ai.domain.agent.flow.middleware.ModelCallMiddlewareAssembler;
import cn.lunalhx.ai.domain.agent.flow.middleware.ModelCallMiddlewareChain;
import cn.lunalhx.ai.domain.agent.flow.middleware.ModelCallOutcome;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerInitializer;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ModelCallNode extends AbstractAgentNode {

    private final ModelCallMiddlewareChain chain;
    private final AgentRuntimeProperties properties;
    private final ConversationLedgerAppendService ledgerAppendService;
    private final ModelPromptFactory promptFactory;
    private final ModelCallBudgetCoordinator budgetCoordinator;

    public ModelCallNode(ModelCallMiddlewareAssembler assembler,
                         AgentRuntimeProperties properties,
                         ConversationLedgerAppendService ledgerAppendService,
                         ModelCallTerminalDeps terminalDeps) {
        super(AgentNodeNames.MODEL_CALL, List.of("stablePrefix", "conversationLedger", "requestId", "conversationId"));
        this.promptFactory = new ModelPromptFactory();
        this.budgetCoordinator = new ModelCallBudgetCoordinator(
                terminalDeps.budgetGuard(), terminalDeps.traceRecorder(), promptFactory);
        this.chain = Objects.requireNonNull(assembler, "assembler must not be null")
                .assemble(ctx -> executeTerminal(ctx, terminalDeps));
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.ledgerAppendService = Objects.requireNonNull(ledgerAppendService, "ledgerAppendService must not be null");
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        long deadlineEpochMs = System.currentTimeMillis()
                + context.runtimeProperties(properties).getStepTimeoutMs();
        String requestedModel = StringUtils.defaultIfBlank(
                context.getRecoveryModelOverride(),
                context.getCurrentModel());
        int escalatedMaxTokens = escalatedMaxTokens(context);

        ModelCallContext ctx = ModelCallContext.of(context, requestedModel, 0, deadlineEpochMs);
        ctx.setEscalatedMaxTokens(escalatedMaxTokens);

        ModelCallOutcome outcome = chain.execute(ctx);
        List<AgentEvent> events = ctx.getEvents();

        switch (outcome.type()) {
            case SUCCESS:
                resetContextRecovery(context);
                context.resetModelCallRetryCount();
                context.recovery().setModelErrorRecoveryAttempted(false);
                String eventKey = ConversationLedgerInitializer.eventKey(
                        context.getRunId(), String.valueOf(context.getStep() + 1), "assistant");
                ledgerAppendService.appendAssistant(
                        context, context.getModelOutput(), eventKey);
                return NodeResult.next(AgentNodeNames.DECISION, events);

            case BUDGET_BLOCKED:
                return NodeResult.next(AgentNodeNames.FAIL, events);

            case TRUNCATION_EXHAUSTED:
                fail(context, AgentStopReason.MODEL_ERROR,
                        ModelErrorCode.MODEL_DECISION_TRUNCATED.code(),
                        ModelErrorCode.MODEL_DECISION_TRUNCATED.defaultMessage());
                if (budgetCoordinator != null) {
                    budgetCoordinator.traceRecovery(context, "model_recovery_exhausted",
                            AgentNodeNames.MODEL_CALL,
                            Map.of("purpose", ModelCallPurpose.CONTROL_JSON.name(),
                                    "finishReason", StringUtils.defaultString("length")));
                }
                return NodeResult.next(AgentNodeNames.FAIL, events);

            case ROUTED:
                return outcome.route();

            case ERROR:
            default:
                return NodeResult.next(AgentNodeNames.FAIL, events);
        }
    }

    private ModelCallOutcome executeTerminal(ModelCallContext ctx, ModelCallTerminalDeps deps) {
        AgentContext context = ctx.getAgentContext();
        ModelCallExecutor executor = new ModelCallExecutor(
                deps.modelGateway(), promptFactory, budgetCoordinator);
        ModelCallResult result = executor.execute(context, ctx.getRequestModel(),
                ctx.getMaxTokens() == null ? 0 : ctx.getMaxTokens(),
                ctx.getDeadlineEpochMs() == null ? 0L : ctx.getDeadlineEpochMs(),
                ctx.getEscalatedMaxTokens() == null ? 8192 : ctx.getEscalatedMaxTokens(), true);

        if (result.isSuccess()) {
            context.setModelOutput(result.chatResult().getContent());
            context.setCurrentModel(result.chatResult().getActualModel());
            context.setFallbackReason(StringUtils.defaultIfBlank(
                    result.chatResult().getFallbackReason(), context.getFallbackReason()));
            return ModelCallOutcome.success();
        }
        if (result.isBudgetBlocked()) {
            return ModelCallOutcome.budgetBlocked();
        }
        if (result.isTruncationExhausted()) {
            return ModelCallOutcome.truncationExhausted();
        }
        if (result.error() != null) {
            throw result.error() instanceof RuntimeException
                    ? (RuntimeException) result.error()
                    : new RuntimeException(result.error());
        }
        return ModelCallOutcome.error("unknown model call error");
    }

    private int escalatedMaxTokens(AgentContext context) {
        AgentRuntimeProperties runProperties = context.runtimeProperties(properties);
        Integer value = runProperties.getModelRecovery() == null
                ? null : runProperties.getModelRecovery().getEscalatedMaxTokens();
        return value == null || value <= 0 ? 8192 : value;
    }

    private void resetContextRecovery(AgentContext context) {
        context.setContextRecoveryStage(ContextRecoveryStage.NONE);
        context.setReactiveCompactAttempts(0);
        context.setRecoveryModelOverride(null);
        if (!Objects.equals(context.getContextTranscriptArtifactId(),
                context.getLedgerBaselineArtifactId())) {
            context.setContextTranscriptArtifactId(null);
        }
        context.setContextBlockedReason(null);
    }
}
