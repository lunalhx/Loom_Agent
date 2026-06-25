package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.DynamicTextEntry;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

class DeepSummaryStrategy implements ContextCompactionStrategy {

    private final AgentRuntimeProperties properties;
    private final ContextArtifactService artifactService;
    private final ContextTranscriptRenderer renderer;
    private final ContextSummaryComposer composer;
    private final ContextSummaryRewriter rewriter;
    private final ContextTokenEstimator estimator;
    private final DeepContextSummaryService deepSummaryService;

    DeepSummaryStrategy(AgentRuntimeProperties properties,
                        ContextArtifactService artifactService,
                        ContextTranscriptRenderer renderer,
                        ContextSummaryComposer composer,
                        ContextSummaryRewriter rewriter,
                        ContextTokenEstimator estimator,
                        DeepContextSummaryService deepSummaryService) {
        this.properties = properties;
        this.artifactService = artifactService;
        this.renderer = renderer;
        this.composer = composer;
        this.rewriter = rewriter;
        this.estimator = estimator;
        this.deepSummaryService = deepSummaryService;
    }

    @Override
    public ContextStrategyResult compact(AgentContext context, ContextCompactionCommand command) {
        List<DynamicTextEntry> entries = new ArrayList<>(context.getDynamicText().entries());
        ContextArtifact transcriptArtifact = artifactService.ensureTranscript(context, renderer.renderEntries(entries));
        String transcript = artifactService.readBlob(transcriptArtifact.getStorageUri());
        String summary;
        String strategy;
        try {
            DeepContextSummaryService.DeepSummaryResult result = deepSummaryService == null
                    ? null
                    : deepSummaryService.summarize(context, renderer.transcriptEntries(transcript), command.deadlineEpochMs());
            if (result == null || StringUtils.isBlank(result.getSummary())) {
                throw new IllegalStateException("deep context summary is unavailable");
            }
            summary = StringUtils.abbreviate(result.getSummary(), estimator.summaryCharsForTarget(command.targetTokens()));
            strategy = "deep_summary";
        } catch (RuntimeException e) {
            summary = composer.compose(context, entries, transcriptArtifact, estimator.summaryCharsForTarget(command.targetTokens()));
            strategy = "deep_summary_deterministic";
        }
        int retained = rewriter.replaceWithSummary(context, entries, summary, "Deep Context Summary",
                positive(contextProperties().getReactiveKeepRecentEntries(), 5), command.targetTokens());
        return new ContextStrategyResult(true, strategy, retained, transcriptArtifact.getArtifactId());
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private AgentRuntimeProperties.ContextProperties contextProperties() {
        if (properties.getContext() == null) {
            properties.setContext(new AgentRuntimeProperties.ContextProperties());
        }
        return properties.getContext();
    }
}
