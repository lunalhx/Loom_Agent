package cn.lunalhx.ai.domain.agent.flow.middleware;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
import cn.lunalhx.ai.domain.agent.service.ledger.ControlUpdateTexts;

import java.util.Objects;

public class DynamicContextMiddleware implements ModelCallMiddleware {

    private final ConversationHistoryAppendService ledgerAppendService;

    public DynamicContextMiddleware(ConversationHistoryAppendService ledgerAppendService) {
        this.ledgerAppendService = Objects.requireNonNull(ledgerAppendService, "ledgerAppendService must not be null");
    }

    @Override
    public ModelCallOutcome apply(ModelCallContext ctx, ModelCallNext next) {
        AgentContext context = ctx.getAgentContext();
        appendBudgetSnapshotIfApplicable(context);
        return next.invoke(ctx);
    }

    private void appendBudgetSnapshotIfApplicable(AgentContext context) {
        String text = ControlUpdateTexts.renderBudgetSnapshot(context);
        if (text.isEmpty()) {
            return;
        }
        String eventKey = ConversationHistoryInitializer.eventKey(
                context.getRunId(), String.valueOf(context.getStep() + 1), "budget");
        ledgerAppendService.appendControlUpdate(context, text, eventKey);
    }
}
