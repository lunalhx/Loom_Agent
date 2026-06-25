package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.DynamicTextEntry;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.DynamicTextRole;
import cn.lunalhx.ai.domain.agent.model.valobj.context.ContextArtifactKind;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class ContextArtifactService {

    private final AgentRuntimeProperties properties;
    private final ContextArtifactRepository artifactRepository;
    private final ContextBlobStore blobStore;

    ContextArtifactService(AgentRuntimeProperties properties,
                           ContextArtifactRepository artifactRepository,
                           ContextBlobStore blobStore) {
        this.properties = properties;
        this.artifactRepository = artifactRepository;
        this.blobStore = blobStore;
    }

    ToolResult prepareToolResult(AgentContext context, ToolResult result) {
        if (result == null || !enabled()) {
            return result;
        }
        String observation = result.getObservation();
        int threshold = positive(contextProperties().getPersistToolResultChars(), 12000);
        if (StringUtils.length(observation) <= threshold) {
            return result;
        }
        ContextArtifact artifact = persist(context, ContextArtifactKind.TOOL_RESULT, observation,
                positive(contextProperties().getToolPreviewChars(), 2000));
        result.setObservation(renderArtifactReference(artifact));
        result.setTruncated(true);
        result.setArtifactId(artifact.getArtifactId());
        result.setOriginalChars(artifact.getOriginalChars());
        result.setRetainedChars(artifact.getRetainedChars());
        result.setSha256(artifact.getSha256());
        return result;
    }

    ContextArtifact ensureTranscript(AgentContext context, String transcript) {
        if (StringUtils.isNotBlank(context.getContextTranscriptArtifactId())) {
            ContextArtifact existing = artifactRepository.findByArtifactIdAndRootRunId(
                    context.getContextTranscriptArtifactId(), context.getRootRunId()).orElse(null);
            if (existing != null) {
                return existing;
            }
        }
        ContextArtifact artifact = persist(context, ContextArtifactKind.TRANSCRIPT, transcript,
                positive(contextProperties().getToolPreviewChars(), 2000));
        context.setContextTranscriptArtifactId(artifact.getArtifactId());
        return artifact;
    }

    ContextArtifact persistEntry(AgentContext context, String content, int previewChars) {
        return persist(context, ContextArtifactKind.CONTEXT_ENTRY, content, previewChars);
    }

    String renderArtifactReference(ContextArtifact artifact) {
        return "[context_artifact]\n"
                + "artifactId=" + artifact.getArtifactId() + "\n"
                + "kind=" + artifact.getKind().name() + "\n"
                + "originalChars=" + artifact.getOriginalChars() + "\n"
                + "sha256=" + artifact.getSha256() + "\n"
                + "preview:\n" + artifact.getPreview() + "\n"
                + "[/context_artifact]\n"
                + "Need full content: call context_recall with action=get, artifactId="
                + artifact.getArtifactId() + ", offset=0, maxChars=<needed>.";
    }

    int artifactCount(AgentContext context) {
        if (context == null || StringUtils.isBlank(context.getRootRunId())) {
            return 0;
        }
        return artifactRepository.listByRootRunId(context.getRootRunId()).size();
    }

    String readBlob(String storageUri) {
        return blobStore.read(storageUri);
    }

    // --- helpers ---

    private ContextArtifact persist(AgentContext context, ContextArtifactKind kind, String content, int previewChars) {
        String artifactId = "ctx-" + UUID.randomUUID();
        String storageUri = blobStore.write(context.getRootRunId(), artifactId, StringUtils.defaultString(content));
        ContextArtifact artifact = ContextArtifact.builder()
                .artifactId(artifactId)
                .runId(context.getRunId())
                .rootRunId(context.getRootRunId())
                .conversationId(context.getConversationId())
                .kind(kind)
                .storageUri(storageUri)
                .preview(StringUtils.abbreviate(StringUtils.defaultString(content), Math.max(64, previewChars)))
                .sha256(DigestUtils.sha256Hex(StringUtils.defaultString(content)))
                .originalChars(StringUtils.length(content))
                .retainedChars(Math.min(StringUtils.length(content), Math.max(64, previewChars)))
                .createdAt(Instant.now())
                .build();
        return artifactRepository.save(artifact);
    }

    private boolean enabled() {
        return Boolean.TRUE.equals(contextProperties().getEnabled());
    }

    private AgentRuntimeProperties.ContextProperties contextProperties() {
        if (properties.getContext() == null) {
            properties.setContext(new AgentRuntimeProperties.ContextProperties());
        }
        return properties.getContext();
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
