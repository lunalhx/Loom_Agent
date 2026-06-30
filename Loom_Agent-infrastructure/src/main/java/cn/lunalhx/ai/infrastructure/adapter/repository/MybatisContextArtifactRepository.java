package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.valobj.context.ContextArtifactKind;
import cn.lunalhx.ai.infrastructure.dao.AgentContextArtifactDao;
import cn.lunalhx.ai.infrastructure.dao.po.AgentContextArtifactPO;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class MybatisContextArtifactRepository implements ContextArtifactRepository {

    private final AgentContextArtifactDao artifactDao;

    public MybatisContextArtifactRepository(AgentContextArtifactDao artifactDao) {
        this.artifactDao = artifactDao;
    }

    @Override
    public ContextArtifact save(ContextArtifact artifact) {
        artifactDao.insert(toPo(artifact));
        return artifact;
    }

    @Override
    public Optional<ContextArtifact> findByArtifactIdAndRootRunId(String artifactId, String rootRunId) {
        return Optional.ofNullable(artifactDao.selectByArtifactIdAndRootRunId(artifactId, rootRunId))
                .map(this::toEntity);
    }

    @Override
    public List<ContextArtifact> listByRootRunId(String rootRunId) {
        return artifactDao.selectByRootRunId(rootRunId).stream().map(this::toEntity).toList();
    }

    @Override
    public List<ContextArtifact> searchByRootRunId(String rootRunId, String query, int limit) {
        return artifactDao.searchByRootRunId(rootRunId, query, limit).stream().map(this::toEntity).toList();
    }

    private AgentContextArtifactPO toPo(ContextArtifact artifact) {
        AgentContextArtifactPO po = new AgentContextArtifactPO();
        po.setArtifactId(artifact.getArtifactId());
        po.setRunId(artifact.getRunId());
        po.setRootRunId(artifact.getRootRunId());
        po.setConversationId(artifact.getConversationId());
        po.setKind(artifact.getKind() == null ? null : artifact.getKind().name());
        po.setStorageUri(artifact.getStorageUri());
        po.setPreview(artifact.getPreview());
        po.setSha256(artifact.getSha256());
        po.setOriginalChars(artifact.getOriginalChars());
        po.setRetainedChars(artifact.getRetainedChars());
        po.setCreateTime(artifact.getCreatedAt());
        return po;
    }

    private ContextArtifact toEntity(AgentContextArtifactPO po) {
        return ContextArtifact.builder()
                .artifactId(po.getArtifactId())
                .runId(po.getRunId())
                .rootRunId(po.getRootRunId())
                .conversationId(po.getConversationId())
                .kind(po.getKind() == null ? null : ContextArtifactKind.valueOf(po.getKind()))
                .storageUri(po.getStorageUri())
                .preview(po.getPreview())
                .sha256(po.getSha256())
                .originalChars(po.getOriginalChars())
                .retainedChars(po.getRetainedChars())
                .createdAt(po.getCreateTime())
                .build();
    }

}
