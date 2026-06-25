package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.DynamicTextEntry;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextCompactResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ContextWindowManager {

    private final AgentRuntimeProperties properties;
    private final ContextWindowComponents components;

    public ContextWindowManager(AgentRuntimeProperties properties,
                                ContextArtifactRepository artifactRepository,
                                ContextBlobStore blobStore) {
        this(properties, artifactRepository, blobStore, null);
    }

    public ContextWindowManager(AgentRuntimeProperties properties,
                                ContextArtifactRepository artifactRepository,
                                ContextBlobStore blobStore,
                                DeepContextSummaryService deepSummaryService) {
        this.properties = properties;
        this.components = ContextWindowComponents.create(properties, artifactRepository, blobStore, deepSummaryService);
    }

    public static ContextWindowManager noop(AgentRuntimeProperties properties) {
        return new ContextWindowManager(properties, new InMemoryContextArtifactRepository(), new InMemoryContextBlobStore());
    }

    public ToolResult prepareToolResult(AgentContext context, ToolResult result) {
        return components.artifactService.prepareToolResult(context, result);
    }

    public ContextCompactResult compactBeforePrompt(AgentContext context) {
        int beforeTokens = estimateTokens(context);
        if (!components.tokenEstimator.enabled() || context == null
                || context.getDynamicText() == null || context.getDynamicText().isEmpty()) {
            return ContextCompactResult.none(beforeTokens);
        }

        Set<String> strategies = new LinkedHashSet<>();
        int beforeArtifacts = components.artifactService.artifactCount(context);
        int targetTokens = positive(contextProperties().getAutoCompactTokenLimit(), 64000);
        ContextCompactionCommand cmd = new ContextCompactionCommand(
                targetTokens,
                positive(contextProperties().getReactiveKeepRecentEntries(), 5),
                0,
                ContextCompactionCommand.ContextCompactionMode.AUTO);

        ContextStrategyResult snipResult = components.snipStrategy.compact(context, cmd);
        if (snipResult.changed()) {
            strategies.add(snipResult.strategy());
        }
        ContextStrategyResult microResult = components.microStrategy.compact(context, cmd);
        if (microResult.changed()) {
            strategies.add(microResult.strategy());
        }

        int afterTokens = estimateTokens(context);
        String transcriptArtifactId = null;
        if (afterTokens > targetTokens) {
            ContextStrategyResult summaryResult = components.deterministicSummaryStrategy.compact(context, cmd);
            strategies.add(summaryResult.strategy());
            afterTokens = estimateTokens(context);
            transcriptArtifactId = summaryResult.transcriptArtifactId();
        }

        int artifactDelta = Math.max(0, components.artifactService.artifactCount(context) - beforeArtifacts);
        if (strategies.isEmpty()) {
            return ContextCompactResult.none(beforeTokens);
        }
        return compactResult(true, beforeTokens, afterTokens, artifactDelta,
                List.copyOf(strategies), targetTokens,
                context.getDynamicText().entries().size(), transcriptArtifactId);
    }

    public ContextCompactResult reactiveCompact(AgentContext context) {
        return reactiveCompact(context, positive(contextProperties().getAutoCompactTokenLimit(), 64000));
    }

    public ContextCompactResult reactiveCompact(AgentContext context, int targetTokens) {
        int beforeTokens = estimateTokens(context);
        if (!components.tokenEstimator.enabled() || context == null
                || context.getDynamicText() == null || context.getDynamicText().isEmpty()) {
            return compactResult(false, beforeTokens, beforeTokens, 0, List.of(),
                    targetTokens, 0, context == null ? null : context.getContextTranscriptArtifactId());
        }
        int beforeArtifacts = components.artifactService.artifactCount(context);
        ContextCompactionCommand cmd = new ContextCompactionCommand(
                targetTokens,
                positive(contextProperties().getReactiveKeepRecentEntries(), 5),
                0,
                ContextCompactionCommand.ContextCompactionMode.REACTIVE);
        ContextStrategyResult result = components.deterministicSummaryStrategy.compact(context, cmd);
        int afterTokens = estimateTokens(context);
        return compactResult(true, beforeTokens, afterTokens,
                Math.max(0, components.artifactService.artifactCount(context) - beforeArtifacts),
                List.of(result.strategy()), targetTokens, result.retainedEntryCount(), result.transcriptArtifactId());
    }

    public ContextCompactResult deepSummaryCompact(AgentContext context,
                                                   int targetTokens,
                                                   long deadlineEpochMs) {
        int beforeTokens = estimateTokens(context);
        if (!components.tokenEstimator.enabled() || context == null
                || context.getDynamicText() == null || context.getDynamicText().isEmpty()) {
            return compactResult(false, beforeTokens, beforeTokens, 0, List.of(),
                    targetTokens, 0, context == null ? null : context.getContextTranscriptArtifactId());
        }
        int beforeArtifacts = components.artifactService.artifactCount(context);
        ContextCompactionCommand cmd = new ContextCompactionCommand(
                targetTokens,
                positive(contextProperties().getReactiveKeepRecentEntries(), 5),
                deadlineEpochMs,
                ContextCompactionCommand.ContextCompactionMode.DEEP);
        ContextStrategyResult result = components.deepSummaryStrategy != null
                ? components.deepSummaryStrategy.compact(context, cmd)
                : fallbackDeepSummary(context, targetTokens);
        int afterTokens = estimateTokens(context);
        return compactResult(true, beforeTokens, afterTokens,
                Math.max(0, components.artifactService.artifactCount(context) - beforeArtifacts),
                List.of(result.strategy()), targetTokens, result.retainedEntryCount(), result.transcriptArtifactId());
    }

    public int estimateTokens(AgentContext context) {
        return components.tokenEstimator.estimateTokens(context);
    }

    // --- internal helpers retained in facade ---

    private ContextStrategyResult fallbackDeepSummary(AgentContext context, int targetTokens) {
        // deepSummaryService 为 null 时的确定性降级（与 DeepSummaryStrategy 的 catch 分支一致）
        List<DynamicTextEntry> entries = new ArrayList<>(context.getDynamicText().entries());
        ContextArtifact transcriptArtifact = components.artifactService.ensureTranscript(context,
                components.renderer.renderEntries(entries));
        String summary = components.composer.compose(context, entries, transcriptArtifact,
                components.tokenEstimator.summaryCharsForTarget(targetTokens));
        int retained = components.rewriter.replaceWithSummary(context, entries, summary, "Deep Context Summary",
                positive(contextProperties().getReactiveKeepRecentEntries(), 5), targetTokens);
        return new ContextStrategyResult(true, "deep_summary_deterministic", retained, transcriptArtifact.getArtifactId());
    }

    private ContextCompactResult compactResult(boolean compacted,
                                               int beforeTokens,
                                               int afterTokens,
                                               int artifactCount,
                                               List<String> strategies,
                                               int targetTokens,
                                               int retainedEntries,
                                               String transcriptArtifactId) {
        return ContextCompactResult.builder()
                .compacted(compacted)
                .beforeEstimatedTokens(beforeTokens)
                .afterEstimatedTokens(afterTokens)
                .artifactCount(artifactCount)
                .strategies(strategies)
                .targetTokens(Math.max(1, targetTokens))
                .fitsTarget(afterTokens <= Math.max(1, targetTokens))
                .retainedEntryCount(retainedEntries)
                .transcriptArtifactId(transcriptArtifactId)
                .build();
    }

    private AgentRuntimeProperties.ContextProperties contextProperties() {
        return components.tokenEstimator.contextProperties();
    }

    private int positive(Integer value, int fallback) {
        return components.tokenEstimator.positive(value, fallback);
    }
}
