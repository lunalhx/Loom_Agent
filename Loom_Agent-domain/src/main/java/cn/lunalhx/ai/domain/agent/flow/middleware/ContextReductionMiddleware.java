package cn.lunalhx.ai.domain.agent.flow.middleware;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.context.ContextBuildResult;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import cn.lunalhx.ai.domain.agent.service.context.PreparedContextView;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a single {@link PreparedContextView} per round via {@link ContextManager}
 * and stores it on the {@link ModelCallContext} so downstream middleware (budget,
 * prompt factory) reuse the identical view.
 *
 * <p>Emits {@code context_compacted} only when a budget reduction was applied
 * (dynamic reduction or floor pressure).
 */
public class ContextReductionMiddleware implements ModelCallMiddleware {

    private final ContextManager contextManager;
    private final ConversationHistoryAppendService ledgerAppendService;
    private final AgentRuntimeProperties properties;

    public ContextReductionMiddleware(ContextManager contextManager,
                                      ConversationHistoryAppendService ledgerAppendService,
                                      AgentRuntimeProperties properties) {
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager must not be null");
        this.ledgerAppendService = ledgerAppendService;
        this.properties = properties;
    }

    @Override
    public ModelCallOutcome apply(ModelCallContext ctx, ModelCallNext next) {
        AgentContext context = ctx.getAgentContext();
        ContextBuildResult result = context.isFloorRetryPending()
                ? contextManager.buildFloorPressed(context)
                : contextManager.build(context);

        if (result.blocked()) {
            context.setContextBlockedReason(result.blockedReason());
            context.waitForRecoveryInput(result.blockedReason(), null);
            AgentEvent event = event(context,
                    "上下文超预算，需补充/拆分请求后继续", result.blockedReason());
            ctx.getEvents().add(event);
            return ModelCallOutcome.routed(
                    cn.lunalhx.ai.domain.agent.flow.NodeResult.next(
                            cn.lunalhx.ai.domain.agent.flow.AgentNodeNames.USER_INPUT_GATE,
                            ctx.getEvents()));
        }

        ctx.setPreparedView(PreparedContextView.from(result));

        if (hasReduction(result)) {
            ctx.getEvents().add(buildReductionEvent(context, result));
        }
        return next.invoke(ctx);
    }

    private boolean hasReduction(ContextBuildResult result) {
        ContextBuildResult.ContextRenderMetadata m = result.metadata();
        return m.reductions() != null && !m.reductions().isEmpty();
    }

    private AgentEvent buildReductionEvent(AgentContext context, ContextBuildResult result) {
        ContextBuildResult.ContextRenderMetadata m = result.metadata();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", "render_only");
        metadata.put("promptRawChars", m.totalChars());
        metadata.put("promptRenderedChars", m.totalChars());
        metadata.put("promptBudgetChars", m.totalBudgetChars());
        metadata.put("sectionBudgets", m.sectionRawChars());
        metadata.put("sectionRenderedChars", m.sectionRenderedChars());
        metadata.put("budgetReductions", m.reductions());
        metadata.put("historyMerged", m.historyMerged());
        metadata.put("historySummarized", m.historySummarized());
        metadata.put("relevantMemorySelected", m.relevantMemorySelected());
        metadata.put("currentRequestChars", m.currentRequestChars());
        metadata.put("currentRequestPreserved", m.currentRequestPreserved());
        return AgentEvent.builder()
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .type(AgentEventType.CONTEXT_COMPACTED)
                .message("Context reduced before model call")
                .metadata(metadata)
                .build();
    }

    private AgentEvent event(AgentContext context, String message, String extra) {
        return AgentEvent.builder()
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .type(AgentEventType.CONTEXT_COMPACTED)
                .message(message)
                .build();
    }
}
