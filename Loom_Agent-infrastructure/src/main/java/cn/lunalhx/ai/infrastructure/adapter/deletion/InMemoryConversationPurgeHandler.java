package cn.lunalhx.ai.infrastructure.adapter.deletion;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class InMemoryConversationPurgeHandler implements ConversationPurgeHandler {

    private static final Logger log = LoggerFactory.getLogger(InMemoryConversationPurgeHandler.class);

    private final ContextArtifactRepository artifactRepository;
    private final ContextBlobStore blobStore;

    public InMemoryConversationPurgeHandler(ContextArtifactRepository artifactRepository,
                                             ContextBlobStore blobStore) {
        this.artifactRepository = artifactRepository;
        this.blobStore = blobStore;
    }

    @Override
    public void purge(String conversationId) {
        // Delete context artifacts and their blobs
        List<ContextArtifact> artifacts = artifactRepository.listByConversationId(conversationId);
        for (ContextArtifact artifact : artifacts) {
            if (artifact.getStorageUri() != null) {
                blobStore.delete(artifact.getStorageUri());
            }
        }
        artifactRepository.deleteByConversationId(conversationId);
        log.info("InMemory purge completed for conversation {}", conversationId);
    }
}
