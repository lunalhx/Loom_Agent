package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerCompactionResult;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerCompactionService;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class ReactiveCompactStep implements ContextRecoveryStep {

    private static final Set<ContextRecoveryStage> ACCEPTABLE_STATES =
            Set.of(ContextRecoveryStage.NONE);

    private final LedgerCompactionService ledgerCompactionService;

    ReactiveCompactStep(LedgerCompactionService ledgerCompactionService) {
        this.ledgerCompactionService = ledgerCompactionService;
    }

    @Override
    public ContextRecoveryTransition apply(ContextRecoveryRequest request, List<AgentEvent> accumulatedEvents) {
        AgentContext context = request.context();
        if (!ACCEPTABLE_STATES.contains(request.stage())) {
            return ContextRecoveryTransition.continueChain();
        }

        LedgerCompactionResult result = ledgerCompactionService.compact(context);
        if (!result.compacted()) {
            return ContextRecoveryTransition.continueChain();
        }

        context.setContextRecoveryStage(ContextRecoveryStage.REACTIVE_COMPACTED);
        context.setContextTranscriptArtifactId(result.transcriptArtifactId());

        AgentEvent event = compactEvent(context, result,
                "Reactive context compact triggered by context length error");
        accumulatedEvents.add(event);

        return ContextRecoveryTransition.renderPrompt(accumulatedEvents);
    }

    private AgentEvent compactEvent(AgentContext context, LedgerCompactionResult result, String message) {
        return AgentEvent.builder()
                .type(AgentEventType.CONTEXT_COMPACTED)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .message(message)
                .metadata(Map.of(
                        "beforeEntryCount", result.beforeEntryCount(),
                        "afterEntryCount", result.afterEntryCount(),
                        "strategy", StringUtils.defaultString(result.strategy()),
                        "generation", result.generation(),
                        "transcriptArtifactId", StringUtils.defaultString(result.transcriptArtifactId())))
                .build();
    }
}
