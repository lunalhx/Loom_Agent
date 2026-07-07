package cn.lunalhx.ai.domain.agent.service.context;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;

class ContextWindowComponents {

    final ContextTokenEstimator tokenEstimator;
    final ContextArtifactService artifactService;

    private ContextWindowComponents(ContextTokenEstimator tokenEstimator,
                                    ContextArtifactService artifactService) {
        this.tokenEstimator = tokenEstimator;
        this.artifactService = artifactService;
    }

    static ContextWindowComponents create(AgentRuntimeProperties properties,
                                          ContextArtifactRepository artifactRepository,
                                          ContextBlobStore blobStore,
                                          DeepContextSummaryService deepSummaryService) {
        ContextTokenEstimator tokenEstimator = new ContextTokenEstimator(properties);
        ContextArtifactService artifactService = new ContextArtifactService(properties, artifactRepository, blobStore);

        return new ContextWindowComponents(tokenEstimator, artifactService);
    }
}
