package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerCompactionResult;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerCompactionService;

import java.util.List;
import java.util.Map;

final class ContextSimplifyStep implements ContextRecoveryStep {

    private final LedgerCompactionService ledgerCompactionService;

    ContextSimplifyStep(LedgerCompactionService ledgerCompactionService) {
        this.ledgerCompactionService = ledgerCompactionService;
    }

    @Override
    public ContextRecoveryTransition apply(ContextRecoveryRequest request, List<AgentEvent> accumulatedEvents) {
        AgentContext context = request.context();

        LedgerCompactionResult result = ledgerCompactionService.compact(context);
        if (!result.compacted()) {
            return ContextRecoveryTransition.continueChain();
        }

        AgentEvent event = AgentEvent.builder()
                .type(AgentEventType.OBSERVATION)
                .code("context_simplified_for_model_error")
                .message("已精简上下文（" + result.beforeEntryCount() + " → " + result.afterEntryCount() + " entries），重试模型调用")
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .metadata(Map.of(
                        "compactionDepth", result.compactionDepth(),
                        "maxInputCompactionDepth", result.maxInputCompactionDepth(),
                        "maxAllowedCompactionDepth", result.maxAllowedCompactionDepth(),
                        "depthGuarded", result.depthGuarded(),
                        "beforeEntryCount", result.beforeEntryCount(),
                        "afterEntryCount", result.afterEntryCount(),
                        "strategy", result.strategy() != null ? result.strategy() : ""))
                .build();
        accumulatedEvents.add(event);
        return ContextRecoveryTransition.renderPrompt(accumulatedEvents);
    }
}
