package cn.lunalhx.ai.domain.agent.service.prompt;

import cn.lunalhx.ai.domain.agent.adapter.port.SkillRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;

import java.util.Objects;

/**
 * Resources needed by {@code RenderPromptNode} for prompt rendering:
 * skill resolution, artifact persistence, and blob storage.
 *
 * <p>Wraps three dependencies into a single parameter so that
 * {@code RenderPromptNode} stays under the 5-parameter constructor limit.
 */
public final class RenderPromptResources {

    private final SkillRepository skillRepository;
    private final ContextArtifactRepository artifactRepository;
    private final ContextBlobStore blobStore;

    public RenderPromptResources(SkillRepository skillRepository,
                                  ContextArtifactRepository artifactRepository,
                                  ContextBlobStore blobStore) {
        this.skillRepository = skillRepository;
        this.artifactRepository = artifactRepository;
        this.blobStore = blobStore;
    }

    /** Test-only factory: all fields null — suitable when skills are never activated. */
    public static RenderPromptResources empty() {
        return new RenderPromptResources(null, null, null);
    }

    /** Test factory with artifact repository and blob store but no skill repository. */
    public static RenderPromptResources withStorage(ContextArtifactRepository artifactRepository,
                                                     ContextBlobStore blobStore) {
        return new RenderPromptResources(null,
                Objects.requireNonNull(artifactRepository, "artifactRepository"),
                Objects.requireNonNull(blobStore, "blobStore"));
    }

    public SkillRepository skillRepository() { return skillRepository; }
    public ContextArtifactRepository artifactRepository() { return artifactRepository; }
    public ContextBlobStore blobStore() { return blobStore; }
}
