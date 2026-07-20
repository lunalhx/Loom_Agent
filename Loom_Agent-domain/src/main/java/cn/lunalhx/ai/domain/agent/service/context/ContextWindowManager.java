package cn.lunalhx.ai.domain.agent.service.context;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.model.ToolResult;

import java.util.Objects;

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
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(artifactRepository, "artifactRepository must not be null");
        Objects.requireNonNull(blobStore, "blobStore must not be null");
        this.components = ContextWindowComponents.create(properties, artifactRepository, blobStore, deepSummaryService);
    }

    public ToolResult prepareToolResult(AgentContext context, ToolResult result) {
        return components.artifactService.prepareToolResult(context, result);
    }

    public int estimateTokens(AgentContext context) {
        return components.tokenEstimator.estimateTokens(context);
    }

}
