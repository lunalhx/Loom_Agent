package cn.lunalhx.ai.domain.agent.flow.middleware;

import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.types.error.ErrorCode;
import cn.lunalhx.ai.domain.agent.flow.node.ContextOverflowChain;
import cn.lunalhx.ai.domain.agent.flow.node.ModelCallFailureClassifier;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * Model-call error handling: routes gateway errors via the context-overflow and
 * model-error overflow chains. Parse/format retries are no longer tracked
 * by a local counter; the global {@code modelAttempts} guard in the main
 * loop terminates exhausted retries as {@code RETRY_LIMIT_REACHED}.
 */
public class ModelCallErrorMiddleware implements ModelCallMiddleware {

    private final ContextOverflowChain overflowChain;
    private final ContextOverflowChain modelErrorOverflowChain;
    private final ModelCallFailureClassifier failureClassifier;
    private final AgentRuntimeProperties properties;
    private final TraceRecorder traceRecorder;

    public ModelCallErrorMiddleware(ContextOverflowChain overflowChain,
                                   ContextOverflowChain modelErrorOverflowChain,
                                   ModelCallFailureClassifier failureClassifier,
                                   AgentRuntimeProperties properties,
                                   TraceRecorder traceRecorder) {
        this.overflowChain = Objects.requireNonNull(overflowChain, "overflowChain must not be null");
        this.modelErrorOverflowChain = Objects.requireNonNull(modelErrorOverflowChain, "modelErrorOverflowChain must not be null");
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.traceRecorder = traceRecorder;
    }

    @Override
    public ModelCallOutcome apply(ModelCallContext ctx, ModelCallNext next) {
        AgentContext context = ctx.getAgentContext();
        long deadlineEpochMs = ctx.getDeadlineEpochMs() != null ? ctx.getDeadlineEpochMs() : 0L;

        ModelCallOutcome outcome;
        try {
            outcome = next.invoke(ctx);
        } catch (Throwable error) {
            return handleError(ctx, context, error, deadlineEpochMs);
        }

        if (outcome.type() == ModelCallOutcome.Type.SUCCESS
                || outcome.type() == ModelCallOutcome.Type.BUDGET_BLOCKED
                || outcome.type() == ModelCallOutcome.Type.TRUNCATION_EXHAUSTED) {
            return outcome;
        }

        if (outcome.type() == ModelCallOutcome.Type.ERROR) {
            Throwable error = new RuntimeException(outcome.errorMessage());
            return handleError(ctx, context, error, deadlineEpochMs);
        }

        return outcome;
    }

    private ModelCallOutcome handleError(ModelCallContext ctx, AgentContext context,
                                          Throwable error, long deadlineEpochMs) {
        int requestedMaxTokens = ctx.getMaxTokens() == null ? 0 : ctx.getMaxTokens();
        ModelCallFailureClassifier.Category category = failureClassifier.classify(error);
        String attemptedModel = failureClassifier.attemptedModel(error);

        switch (category) {
            case BUDGET_EXCEEDED:
                context.setBudgetBlockedReason("fallback model exceeds remaining budget");
                context.blockForBudget("budget_exceeded",
                        ModelErrorCode.BUDGET_EXCEEDED.defaultMessage());
                return ModelCallOutcome.budgetBlocked();

            case TIMEOUT:
                context.runtime().fail(AgentStopReason.TIMEOUT,
                        ModelErrorCode.MODEL_CALL_TIMEOUT.code(),
                        ModelErrorCode.MODEL_CALL_TIMEOUT.defaultMessage());
                return ModelCallOutcome.error("模型调用超时");

            case CONTEXT_OVERFLOW: {
                NodeResult recoveryRoute = overflowChain.execute(context,
                        StringUtils.defaultIfBlank(attemptedModel, context.getRecoveryModelOverride()),
                        requestedMaxTokens, deadlineEpochMs);
                return ModelCallOutcome.routed(recoveryRoute);
            }

            case GATEWAY_ERROR: {
                ModelGatewayException gatewayException = failureClassifier.modelGatewayException(error);
                ErrorCode errorCode = gatewayException.getErrorCode() == null
                        ? ModelErrorCode.MODEL_ERROR : gatewayException.getErrorCode();
                context.runtime().fail(AgentStopReason.MODEL_ERROR, errorCode.code(),
                        StringUtils.defaultIfBlank(gatewayException.getMessage(), errorCode.defaultMessage()));
                return ModelCallOutcome.error(errorCode.code());
            }

            default: {
                if (!context.modelCall().modelErrorOverflowAttempted()) {
                    context.modelCall().setModelErrorOverflowAttempted(true);
                    NodeResult recoveryRouteResult = modelErrorOverflowChain.execute(context,
                            StringUtils.defaultIfBlank(
                                    context.getRecoveryModelOverride(),
                                    context.getCurrentModel()),
                            requestedMaxTokens, deadlineEpochMs);
                    return ModelCallOutcome.routed(recoveryRouteResult);
                }
                context.runtime().fail(AgentStopReason.MODEL_ERROR, "model_error",
                        "模型决策失败，恢复链已耗尽");
                return ModelCallOutcome.error("model_error");
            }
        }
    }
}