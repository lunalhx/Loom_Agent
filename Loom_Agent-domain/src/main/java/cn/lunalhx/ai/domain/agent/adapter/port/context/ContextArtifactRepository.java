package cn.lunalhx.ai.domain.agent.adapter.port.context;

import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.valobj.context.ContextArtifactKind;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ContextArtifactRepository {

    ContextArtifact save(ContextArtifact artifact);

    Optional<ContextArtifact> findByArtifactIdAndRootRunId(String artifactId, String rootRunId);

    List<ContextArtifact> listByRootRunId(String rootRunId);

    List<ContextArtifact> searchByRootRunId(String rootRunId, String query, int limit);

    List<ContextArtifact> listByConversationId(String conversationId);

    List<ContextArtifact> listByConversationIdAndKind(String conversationId, ContextArtifactKind kind);

    List<ContextArtifact> listExpiredByKind(ContextArtifactKind kind, Instant cutoff, int limit);

    int deleteByArtifactIdAndRootRunId(String artifactId, String rootRunId);

    int deleteByConversationId(String conversationId);

}
