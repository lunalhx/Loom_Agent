package cn.lunalhx.ai.domain.agent.service.context;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.valobj.context.ContextArtifactKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class ContextArtifactPurgeService {

    private static final Logger log = LoggerFactory.getLogger(ContextArtifactPurgeService.class);

    private final ContextArtifactRepository artifactRepository;
    private final ContextBlobStore blobStore;

    public ContextArtifactPurgeService(ContextArtifactRepository artifactRepository,
                                        ContextBlobStore blobStore) {
        this.artifactRepository = artifactRepository;
        this.blobStore = blobStore;
    }

    /**
     * Non-fatal purge: delete blob first, then metadata. Blob failures are logged
     * and metadata is kept for retry. Returns the count of fully purged artifacts.
     */
    public int purgeArtifactsNonFatal(List<ContextArtifact> artifacts) {
        int purged = 0;
        for (ContextArtifact artifact : artifacts) {
            if (!deleteBlobSafe(artifact)) {
                continue;
            }
            if (deleteMetadata(artifact)) {
                purged++;
            }
        }
        return purged;
    }

    /**
     * Strict purge for a single artifact: delete blob, then metadata.
     * Throws {@link IllegalStateException} on blob deletion failure.
     */
    public void purgeArtifactStrict(ContextArtifact artifact) {
        deleteBlobStrict(artifact);
        artifactRepository.deleteByArtifactIdAndRootRunId(artifact.getArtifactId(), artifact.getRootRunId());
    }

    private boolean deleteBlobSafe(ContextArtifact artifact) {
        if (artifact.getStorageUri() == null) {
            return true;
        }
        try {
            blobStore.delete(artifact.getStorageUri());
            return true;
        } catch (Exception e) {
            log.warn("Failed to delete blob {} for artifact {} (will retry): {}",
                    artifact.getStorageUri(), artifact.getArtifactId(), e.getMessage());
            return false;
        }
    }

    private void deleteBlobStrict(ContextArtifact artifact) {
        if (artifact.getStorageUri() == null) {
            return;
        }
        blobStore.delete(artifact.getStorageUri());
    }

    private boolean deleteMetadata(ContextArtifact artifact) {
        int rows = artifactRepository.deleteByArtifactIdAndRootRunId(
                artifact.getArtifactId(), artifact.getRootRunId());
        return rows > 0;
    }
}
