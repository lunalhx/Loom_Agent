package cn.lunalhx.ai.domain.agent.flow.middleware;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerInitializer;
import cn.lunalhx.ai.domain.agent.service.ledger.ControlUpdateTexts;

import java.util.Objects;

public class DynamicContextMiddleware implements ModelCallMiddleware {

    private final ConversationLedgerAppendService ledgerAppendService;

    public DynamicContextMiddleware(ConversationLedgerAppendService ledgerAppendService) {
        this.ledgerAppendService = Objects.requireNonNull(ledgerAppendService, "ledgerAppendService must not be null");
    }

    @Override
    public ModelCallOutcome apply(ModelCallContext ctx, ModelCallNext next) {
        AgentContext context = ctx.getAgentContext();
        appendBudgetSnapshotIfApplicable(context);
        appendTodoReminderIfTriggered(context);
        return next.invoke(ctx);
    }

    private void appendBudgetSnapshotIfApplicable(AgentContext context) {
        String text = ControlUpdateTexts.renderBudgetSnapshot(context);
        if (text.isEmpty()) {
            return;
        }
        String eventKey = ConversationLedgerInitializer.eventKey(
                context.getRunId(), String.valueOf(context.getStep() + 1), "budget");
        ledgerAppendService.appendControlUpdate(context, text, eventKey);
    }

    private void appendTodoReminderIfTriggered(AgentContext context) {
        if (context.getPlan() == null || context.getPlan().getRoundsSinceUpdate() < 3) {
            return;
        }
        String text = ControlUpdateTexts.renderTodoReminder();
        String eventKey = ConversationLedgerInitializer.eventKey(
                context.getRunId(), String.valueOf(context.getStep() + 1), "todo_reminder");
        ledgerAppendService.appendControlUpdate(context, text, eventKey);
    }
}
