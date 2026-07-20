package cn.lunalhx.ai.domain.agent.flow.middleware;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerCompactionResult;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerCompactionService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class SummarizationMiddleware implements ModelCallMiddleware {

    private final LedgerCompactionService compactionService;

    public SummarizationMiddleware(LedgerCompactionService compactionService) {
        this.compactionService = Objects.requireNonNull(compactionService, "compactionService must not be null");
    }

    @Override
    public ModelCallOutcome apply(ModelCallContext ctx, ModelCallNext next) {
        AgentContext context = ctx.getAgentContext();
        try {
            LedgerCompactionResult result = compactionService.compactIfNeeded(context);
            if (result.compacted()) {
                AgentEvent event = buildCompactEvent(context, result);
                ctx.getEvents().add(event);
            }
        } catch (Exception e) {
            // Fail-open: compaction failure must not interrupt the agent
        }
        return next.invoke(ctx);
    }

    private AgentEvent buildCompactEvent(AgentContext context, LedgerCompactionResult result) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("compactionType", "ledger");
        metadata.put("generation", result.generation());
        metadata.put("beforeEntryCount", result.beforeEntryCount());
        metadata.put("afterEntryCount", result.afterEntryCount());
        metadata.put("strategy", result.strategy() == null ? "" : result.strategy());
        metadata.put("transcriptArtifactId", result.transcriptArtifactId() == null
                ? "" : result.transcriptArtifactId());
        metadata.put("compactionDepth", result.compactionDepth());
        metadata.put("maxInputCompactionDepth", result.maxInputCompactionDepth());
        metadata.put("maxAllowedCompactionDepth", result.maxAllowedCompactionDepth());
        metadata.put("depthGuarded", result.depthGuarded());
        metadata.put("estimatedTokens", result.estimatedTokens());
        metadata.put("tokenLimit", result.tokenLimit());
        return AgentEvent.builder()
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .type(AgentEventType.CONTEXT_COMPACTED)
                .message("Ledger compacted before model call")
                .metadata(metadata)
                .build();
    }
}
