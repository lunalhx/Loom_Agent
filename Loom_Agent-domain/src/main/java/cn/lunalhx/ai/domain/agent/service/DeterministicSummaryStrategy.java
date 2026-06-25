package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.DynamicTextEntry;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.DynamicTextRole;

import java.util.ArrayList;
import java.util.List;

class DeterministicSummaryStrategy implements ContextCompactionStrategy {

    private final AgentRuntimeProperties properties;
    private final ContextArtifactService artifactService;
    private final ContextTranscriptRenderer renderer;
    private final ContextSummaryComposer composer;
    private final ContextSummaryRewriter rewriter;
    private final ContextTokenEstimator estimator;

    DeterministicSummaryStrategy(AgentRuntimeProperties properties,
                                 ContextArtifactService artifactService,
                                 ContextTranscriptRenderer renderer,
                                 ContextSummaryComposer composer,
                                 ContextSummaryRewriter rewriter,
                                 ContextTokenEstimator estimator) {
        this.properties = properties;
        this.artifactService = artifactService;
        this.renderer = renderer;
        this.composer = composer;
        this.rewriter = rewriter;
        this.estimator = estimator;
    }

    @Override
    public ContextStrategyResult compact(AgentContext context, ContextCompactionCommand command) {
        List<DynamicTextEntry> entries = new ArrayList<>(context.getDynamicText().entries());
        ContextArtifact transcriptArtifact = artifactService.ensureTranscript(context, renderer.renderEntries(entries));

        if (command.mode() == ContextCompactionCommand.ContextCompactionMode.AUTO) {
            return autoCompact(context, entries, transcriptArtifact, command);
        } else {
            return reactiveCompact(context, entries, transcriptArtifact, command);
        }
    }

    private ContextStrategyResult autoCompact(AgentContext context,
                                               List<DynamicTextEntry> entries,
                                               ContextArtifact transcriptArtifact,
                                               ContextCompactionCommand command) {
        List<DynamicTextEntry> next = new ArrayList<>();
        entries.stream()
                .filter(entry -> entry.getRole() == DynamicTextRole.USER_TASK)
                .findFirst()
                .ifPresent(next::add);
        next.add(renderer.systemNote("Context Summary Compact",
                composer.compose(context, entries, transcriptArtifact,
                        positive(contextProperties().getSummaryMaxChars(), 6000))));

        int keepRecent = Math.max(4, positive(contextProperties().getKeepRecentToolResults(), 4) * 2);
        int start = Math.max(0, entries.size() - keepRecent);
        for (DynamicTextEntry entry : entries.subList(start, entries.size())) {
            if (entry.getRole() != DynamicTextRole.USER_TASK) {
                next.add(entry);
            }
        }
        context.getDynamicText().replaceEntries(next);
        return new ContextStrategyResult(true, "summary", next.size(), transcriptArtifact.getArtifactId());
    }

    private ContextStrategyResult reactiveCompact(AgentContext context,
                                                   List<DynamicTextEntry> entries,
                                                   ContextArtifact transcriptArtifact,
                                                   ContextCompactionCommand command) {
        int maxChars = estimator.summaryCharsForTarget(command.targetTokens());
        String summary = composer.compose(context, entries, transcriptArtifact, maxChars);
        int retained = rewriter.replaceWithSummary(context, entries, summary,
                "Reactive Context Compact",
                positive(contextProperties().getReactiveKeepRecentEntries(), 5),
                command.targetTokens());
        return new ContextStrategyResult(true, "reactive_summary", retained, transcriptArtifact.getArtifactId());
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
