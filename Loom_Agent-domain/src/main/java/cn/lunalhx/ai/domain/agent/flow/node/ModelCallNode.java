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
import cn.lunalhx.ai.domain.agent.service.context.PreparedContextView;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Budget check, model call, and model-level recovery. Consumes the prepared
 * view built by {@link PromptBuildNode} so budget estimation matches the
 * actual request.
 */
public class ModelCallNode extends AbstractAgentNode {

    private final ModelCallMiddlewareChain chain;
    private final AgentRuntimeProperties properties;
    private final ConversationHistoryAppendService ledgerAppendService;
    private final ModelPromptFactory promptFactory;
    private final ModelCallBudgetCoordinator budgetCoordinator;

    public ModelCallNode(ModelCallMiddlewareAssembler assembler,
                         AgentRuntimeProperties properties,
                         ConversationHistoryAppendService ledgerAppendService,
                         ModelCallTerminalDeps terminalDeps) {
        super(AgentNodeNames.MODEL_CALL, List.of("stablePrefix", "conversationHistory", "requestId", "conversationId"));
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
        PreparedContextView prepared = context.getPreparedView();
        if (prepared != null) {
            ctx.setPreparedView(prepared);
        }

        ModelCallOutcome outcome = chain.execute(ctx);
        List<AgentEvent> events = ctx.getEvents();

        switch (outcome.type()) {
            case SUCCESS:
                resetContextRecovery(context);
                String eventKey = ConversationHistoryInitializer.eventKey(
                        context.getRunId(), String.valueOf(context.getModelAttempts()), "assistant");
                ledgerAppendService.appendAssistant(
                        context, context.getModelOutput(), eventKey);
                return NodeResult.nextNode(AgentNodeNames.DECISION, events);

            case BUDGET_BLOCKED:
                return NodeResult.fail(events);

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
                return NodeResult.fail(events);

            case ROUTED:
                return outcome.route();

            case ERROR:
            default:
                return NodeResult.fail(events);
        }
    }

    private ModelCallOutcome executeTerminal(ModelCallContext ctx, ModelCallTerminalDeps deps) {
        AgentContext context = ctx.getAgentContext();
        ModelCallExecutor executor = new ModelCallExecutor(
                deps.modelGateway(), promptFactory, budgetCoordinator);
        ModelCallResult result = executor.execute(context, ctx.getRequestModel(),
                ctx.getMaxTokens() == null ? 0 : ctx.getMaxTokens(),
                ctx.getDeadlineEpochMs() == null ? 0L : ctx.getDeadlineEpochMs(),
                ctx.getEscalatedMaxTokens() == null ? 8192 : ctx.getEscalatedMaxTokens(), true,
                ctx.getPreparedView());

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
        context.setContextTranscriptArtifactId(null);
        context.setContextBlockedReason(null);
        context.setFloorRetryPending(false);
    }
}