package cn.lunalhx.ai.domain.agent.service.prompt;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.service.context.ContextWindowManager;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerAppendService;

import java.util.Objects;

/**
 * Services consumed by {@code ModelCallNode}: tracing, budget, context window, and ledger append.
 *
 * <p>Wraps four dependencies into a single parameter so that
 * {@code ModelCallNode} stays under the 5-parameter constructor limit.
 */
public final class ModelCallServices {

    private final TraceRecorder traceRecorder;
    private final BudgetGuard budgetGuard;
    private final ContextWindowManager contextWindowManager;
    private final ConversationLedgerAppendService ledgerAppendService;

    public ModelCallServices(TraceRecorder traceRecorder,
                              BudgetGuard budgetGuard,
                              ContextWindowManager contextWindowManager,
                              ConversationLedgerAppendService ledgerAppendService) {
        this.traceRecorder = traceRecorder;
        this.budgetGuard = budgetGuard;
        this.contextWindowManager = contextWindowManager;
        this.ledgerAppendService = Objects.requireNonNull(
                ledgerAppendService, "ledgerAppendService must not be null");
    }

    public TraceRecorder traceRecorder() { return traceRecorder; }
    public BudgetGuard budgetGuard() { return budgetGuard; }
    public ContextWindowManager contextWindowManager() { return contextWindowManager; }
    public ConversationLedgerAppendService ledgerAppendService() { return ledgerAppendService; }
}
