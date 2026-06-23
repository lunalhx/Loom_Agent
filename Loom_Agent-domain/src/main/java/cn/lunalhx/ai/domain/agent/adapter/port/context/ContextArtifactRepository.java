package cn.lunalhx.ai.domain.agent.adapter.port.context;

import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;

import java.util.List;
import java.util.Optional;

public interface ContextArtifactRepository {

    ContextArtifact save(ContextArtifact artifact);

    Optional<ContextArtifact> findByArtifactIdAndRootRunId(String artifactId, String rootRunId);

    List<ContextArtifact> listByRootRunId(String rootRunId);

    List<ContextArtifact> searchByRootRunId(String rootRunId, String query, int limit);

}
