package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;

class ContextWindowComponents {

    final ContextTokenEstimator tokenEstimator;
    final ContextArtifactService artifactService;
    final ContextTranscriptRenderer renderer;
    final ContextSummaryComposer composer;
    final ContextSummaryRewriter rewriter;
    final SnipCompactionStrategy snipStrategy;
    final MicroCompactionStrategy microStrategy;
    final DeterministicSummaryStrategy deterministicSummaryStrategy;
    final DeepSummaryStrategy deepSummaryStrategy;

    private ContextWindowComponents(ContextTokenEstimator tokenEstimator,
                                    ContextArtifactService artifactService,
                                    ContextTranscriptRenderer renderer,
                                    ContextSummaryComposer composer,
                                    ContextSummaryRewriter rewriter,
                                    SnipCompactionStrategy snipStrategy,
                                    MicroCompactionStrategy microStrategy,
                                    DeterministicSummaryStrategy deterministicSummaryStrategy,
                                    DeepSummaryStrategy deepSummaryStrategy) {
        this.tokenEstimator = tokenEstimator;
        this.artifactService = artifactService;
        this.renderer = renderer;
        this.composer = composer;
        this.rewriter = rewriter;
        this.snipStrategy = snipStrategy;
        this.microStrategy = microStrategy;
        this.deterministicSummaryStrategy = deterministicSummaryStrategy;
        this.deepSummaryStrategy = deepSummaryStrategy;
    }

    static ContextWindowComponents create(AgentRuntimeProperties properties,
                                          ContextArtifactRepository artifactRepository,
                                          ContextBlobStore blobStore,
                                          DeepContextSummaryService deepSummaryService) {
        ContextTokenEstimator tokenEstimator = new ContextTokenEstimator(properties);
        ContextArtifactService artifactService = new ContextArtifactService(properties, artifactRepository, blobStore);
        ContextTranscriptRenderer renderer = new ContextTranscriptRenderer();
        ContextSummaryComposer composer = new ContextSummaryComposer(properties);
        ContextSummaryRewriter rewriter = new ContextSummaryRewriter(properties, artifactService, renderer);

        SnipCompactionStrategy snipStrategy = new SnipCompactionStrategy(properties, renderer);
        MicroCompactionStrategy microStrategy = new MicroCompactionStrategy(properties);
        DeterministicSummaryStrategy deterministicSummaryStrategy = new DeterministicSummaryStrategy(
                properties, artifactService, renderer, composer, rewriter, tokenEstimator);
        DeepSummaryStrategy deepSummaryStrategy = new DeepSummaryStrategy(
                properties, artifactService, renderer, composer, rewriter, tokenEstimator, deepSummaryService);

        return new ContextWindowComponents(tokenEstimator, artifactService, renderer, composer, rewriter,
                snipStrategy, microStrategy, deterministicSummaryStrategy, deepSummaryStrategy);
    }
}
