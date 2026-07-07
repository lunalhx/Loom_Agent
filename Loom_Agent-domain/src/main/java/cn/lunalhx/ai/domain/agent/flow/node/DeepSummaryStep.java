package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerCompactionResult;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerCompactionService;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class DeepSummaryStep implements ContextRecoveryStep {

    private static final Set<ContextRecoveryStage> ACCEPTABLE_STATES =
            Set.of(ContextRecoveryStage.NONE, ContextRecoveryStage.REACTIVE_COMPACTED,
                    ContextRecoveryStage.FALLBACK_MODEL_SELECTED);

    private final AgentRuntimeProperties properties;
    private final LedgerCompactionService ledgerCompactionService;
    private final ModelGateway modelGateway;

    DeepSummaryStep(AgentRuntimeProperties properties, LedgerCompactionService ledgerCompactionService,
                    ModelGateway modelGateway) {
        this.properties = properties;
        this.ledgerCompactionService = ledgerCompactionService;
        this.modelGateway = modelGateway;
    }

    @Override
    public ContextRecoveryTransition apply(ContextRecoveryRequest request, List<AgentEvent> accumulatedEvents) {
        AgentContext context = request.context();
        if (!ACCEPTABLE_STATES.contains(request.stage())) {
            return ContextRecoveryTransition.continueChain();
        }

        LedgerCompactionResult compactResult = ledgerCompactionService.compact(context);
        context.setContextRecoveryStage(ContextRecoveryStage.DEEP_SUMMARY_APPLIED);
        context.setContextTranscriptArtifactId(compactResult.transcriptArtifactId());

        AgentEvent event = compactEvent(context, compactResult,
                "Deep context summary applied after context recovery was exhausted");
        accumulatedEvents.add(event);

        if (compactResult.compacted()) {
            return ContextRecoveryTransition.renderPrompt(accumulatedEvents);
        }
        return ContextRecoveryTransition.continueChain();
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
                        "strategy", result.strategy() == null ? "" : result.strategy(),
                        "transcriptArtifactId", StringUtils.defaultString(result.transcriptArtifactId())))
                .build();
    }
}
