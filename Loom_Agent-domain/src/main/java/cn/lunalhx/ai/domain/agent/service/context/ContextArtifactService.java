package cn.lunalhx.ai.domain.agent.service.context;

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
        return "<persisted-output"
                + " artifactId=\"" + artifact.getArtifactId() + "\""
                + " kind=\"" + artifact.getKind().name() + "\""
                + " originalChars=\"" + artifact.getOriginalChars() + "\""
                + " retainedChars=\"" + artifact.getRetainedChars() + "\""
                + " sha256=\"" + artifact.getSha256() + "\""
                + " />\n"
                + "Preview (first " + artifact.getRetainedChars() + " chars):\n"
                + "<persisted_content>\n"
                + artifact.getPreview() + "\n"
                + "</persisted_content>\n"
                + "Full content: context_recall(action=get, artifactId="
                + artifact.getArtifactId() + ", offset=0, maxChars=<needed>)";
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

    /**
     * Computes a pure prefix preview without abbreviation ellipsis.
     * Length = min(previewChars, 2000, persistThreshold, content.length()).
     * Does not split Unicode surrogate pairs.
     */
    static String previewPrefix(String content, int previewChars, int persistThreshold) {
        String safe = StringUtils.defaultString(content);
        int maxLen = Math.min(previewChars,
                Math.min(2000,
                        Math.min(persistThreshold, safe.length())));
        while (maxLen > 0 && maxLen < safe.length()
                && Character.isHighSurrogate(safe.charAt(maxLen - 1))) {
            maxLen--;
        }
        return safe.substring(0, maxLen);
    }

    private ContextArtifact persist(AgentContext context, ContextArtifactKind kind, String content, int previewChars) {
        String artifactId = "ctx-" + UUID.randomUUID();
        String safeContent = StringUtils.defaultString(content);
        String storageUri = blobStore.write(context.getRootRunId(), artifactId, safeContent);
        int persistThreshold = positive(contextProperties().getPersistToolResultChars(), 12000);
        String preview = previewPrefix(content, previewChars, persistThreshold);
        ContextArtifact artifact = ContextArtifact.builder()
                .artifactId(artifactId)
                .runId(context.getRunId())
                .rootRunId(context.getRootRunId())
                .conversationId(context.getConversationId())
                .kind(kind)
                .storageUri(storageUri)
                .preview(preview)
                .sha256(DigestUtils.sha256Hex(safeContent))
                .originalChars(StringUtils.length(content))
                .retainedChars(preview.length())
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
