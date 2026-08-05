package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;

import java.util.List;
import java.util.Objects;

/**
 * Factory for creating {@link ContextRecoveryChain} instances used by
 * {@link cn.lunalhx.ai.domain.agent.flow.middleware.ErrorRecoveryMiddleware}.
 */
public final class RecoveryChainFactory {

    private RecoveryChainFactory() {
    }

    public static ContextRecoveryChain createRecoveryChain(
            AgentRuntimeProperties properties,
            BudgetGuard budgetGuard,
            TraceRecorder traceRecorder,
            ContextManager contextManager) {
        Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(contextManager, "contextManager must not be null");

        return new ContextRecoveryChain(List.of(
                new FloorRetryStep(properties, contextManager),
                new ExhaustedStep()
        ));
    }

    public static ContextRecoveryChain createModelErrorRecoveryChain(
            ConversationHistoryAppendService ledgerAppendService,
            ModelRuntimeProperties modelRuntimeProperties) {
        Objects.requireNonNull(ledgerAppendService, "ledgerAppendService must not be null");
        Objects.requireNonNull(modelRuntimeProperties, "modelRuntimeProperties must not be null");

        return new ContextRecoveryChain(List.of(
                new FormatReminderStep(ledgerAppendService),
                new ModelFallbackStep(modelRuntimeProperties),
                new ModelErrorExhaustedStep()
        ));
    }
}