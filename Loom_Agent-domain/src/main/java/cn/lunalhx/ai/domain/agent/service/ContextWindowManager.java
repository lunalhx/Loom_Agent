package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.DynamicTextEntry;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextCompactResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.DynamicTextRole;
import cn.lunalhx.ai.domain.agent.model.valobj.context.ContextArtifactKind;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ContextWindowManager {

    private final AgentRuntimeProperties properties;
    private final ContextArtifactRepository artifactRepository;
    private final ContextBlobStore blobStore;

    public ContextWindowManager(AgentRuntimeProperties properties,
                                ContextArtifactRepository artifactRepository,
                                ContextBlobStore blobStore) {
        this.properties = properties;
        this.artifactRepository = artifactRepository;
        this.blobStore = blobStore;
    }

    public static ContextWindowManager noop(AgentRuntimeProperties properties) {
        return new ContextWindowManager(properties, new InMemoryContextArtifactRepository(), new InMemoryContextBlobStore());
    }

    public ToolResult prepareToolResult(AgentContext context, ToolResult result) {
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

    public ContextCompactResult compactBeforePrompt(AgentContext context) {
        int beforeTokens = estimateTokens(context);
        if (!enabled() || context == null || context.getDynamicText() == null || context.getDynamicText().isEmpty()) {
            return ContextCompactResult.none(beforeTokens);
        }

        Set<String> strategies = new LinkedHashSet<>();
        List<DynamicTextEntry> entries = new ArrayList<>(context.getDynamicText().entries());
        int beforeArtifacts = artifactCount(context);
        if (microCompact(entries)) {
            strategies.add("micro");
        }
        if (snipCompact(entries)) {
            strategies.add("snip");
        }
        context.getDynamicText().replaceEntries(entries);

        int afterTokens = estimateTokens(context);
        if (afterTokens > positive(contextProperties().getAutoCompactTokenLimit(), 64000)) {
            summaryCompact(context);
            strategies.add("summary");
            afterTokens = estimateTokens(context);
        }
        int artifactDelta = Math.max(0, artifactCount(context) - beforeArtifacts);
        if (strategies.isEmpty()) {
            return ContextCompactResult.none(beforeTokens);
        }
        return ContextCompactResult.builder()
                .compacted(true)
                .beforeEstimatedTokens(beforeTokens)
                .afterEstimatedTokens(afterTokens)
                .artifactCount(artifactDelta)
                .strategies(List.copyOf(strategies))
                .build();
    }

    public ContextCompactResult reactiveCompact(AgentContext context) {
        int beforeTokens = estimateTokens(context);
        if (!enabled() || context == null || context.getDynamicText() == null || context.getDynamicText().isEmpty()) {
            return ContextCompactResult.none(beforeTokens);
        }
        int beforeArtifacts = artifactCount(context);
        summaryCompact(context);
        int afterTokens = estimateTokens(context);
        return ContextCompactResult.builder()
                .compacted(true)
                .beforeEstimatedTokens(beforeTokens)
                .afterEstimatedTokens(afterTokens)
                .artifactCount(Math.max(0, artifactCount(context) - beforeArtifacts))
                .strategies(List.of("reactive_summary"))
                .build();
    }

    private boolean microCompact(List<DynamicTextEntry> entries) {
        int keepRecent = positive(contextProperties().getKeepRecentToolResults(), 4);
        int seenToolResults = 0;
        boolean changed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            DynamicTextEntry entry = entries.get(i);
            if (entry.getRole() != DynamicTextRole.TOOL_RESULT) {
                continue;
            }
            seenToolResults++;
            if (seenToolResults <= keepRecent || Boolean.TRUE.equals(entry.getCompacted())) {
                continue;
            }
            if (StringUtils.isBlank(entry.getArtifactId())) {
                continue;
            }
            String content = "[compacted_tool_result]\n"
                    + "artifactId=" + entry.getArtifactId() + "\n"
                    + "originalChars=" + nullToZero(entry.getOriginalChars()) + "\n"
                    + "Use context_recall get with this artifactId when exact output is needed.";
            entry.setSummary(content);
            entry.setContent(content);
            entry.setRenderChars(content.length());
            entry.setCompacted(true);
            changed = true;
        }
        return changed;
    }

    private boolean snipCompact(List<DynamicTextEntry> entries) {
        int maxEntries = positive(contextProperties().getMaxDynamicEntries(), 60);
        if (entries.size() <= maxEntries) {
            return false;
        }
        int tailSize = Math.max(10, maxEntries - 2);
        List<DynamicTextEntry> next = new ArrayList<>();
        entries.stream()
                .filter(entry -> entry.getRole() == DynamicTextRole.USER_TASK)
                .findFirst()
                .ifPresent(next::add);
        int omitted = Math.max(0, entries.size() - tailSize - next.size());
        next.add(systemNote("Context Snip Compact",
                "Omitted " + omitted + " older dynamic context entries. "
                        + "Artifact-backed outputs remain recoverable through context_recall."));
        next.addAll(entries.subList(Math.max(0, entries.size() - tailSize), entries.size()));
        entries.clear();
        entries.addAll(next);
        return true;
    }

    private void summaryCompact(AgentContext context) {
        List<DynamicTextEntry> entries = new ArrayList<>(context.getDynamicText().entries());
        String transcript = context.getDynamicText().render();
        ContextArtifact transcriptArtifact = persist(context, ContextArtifactKind.TRANSCRIPT, transcript,
                positive(contextProperties().getToolPreviewChars(), 2000));

        List<DynamicTextEntry> next = new ArrayList<>();
        entries.stream()
                .filter(entry -> entry.getRole() == DynamicTextRole.USER_TASK)
                .findFirst()
                .ifPresent(next::add);
        next.add(systemNote("Context Summary Compact", summaryText(context, entries, transcriptArtifact)));

        int keepRecent = Math.max(4, positive(contextProperties().getKeepRecentToolResults(), 4) * 2);
        int start = Math.max(0, entries.size() - keepRecent);
        for (DynamicTextEntry entry : entries.subList(start, entries.size())) {
            if (entry.getRole() != DynamicTextRole.USER_TASK) {
                next.add(entry);
            }
        }
        context.getDynamicText().replaceEntries(next);
    }

    private String summaryText(AgentContext context, List<DynamicTextEntry> entries, ContextArtifact transcriptArtifact) {
        StringBuilder text = new StringBuilder();
        text.append("UserGoal: ").append(StringUtils.abbreviate(StringUtils.defaultString(context.getQuestion()), 800)).append('\n');
        if (context.getPlan() != null) {
            text.append("CurrentPlan:\n").append(StringUtils.abbreviate(context.getPlan().render(),
                    positive(contextProperties().getSummaryMaxChars(), 6000) / 2)).append('\n');
        }
        text.append("CompactedTranscriptArtifactId: ").append(transcriptArtifact.getArtifactId()).append('\n');
        text.append("DynamicEntriesBeforeSummary: ").append(entries.size()).append('\n');
        text.append("RecentTools:");
        List<DynamicTextEntry> recentTools = entries.stream()
                .filter(entry -> entry.getRole() == DynamicTextRole.ASSISTANT_ACTION || entry.getRole() == DynamicTextRole.TOOL_RESULT)
                .toList();
        recentTools.stream()
                .skip(Math.max(0, recentTools.size() - 12L))
                .forEach(entry -> text.append("\n- step=").append(entry.getStep())
                        .append(" role=").append(entry.getRole().code())
                        .append(" tool=").append(StringUtils.defaultString(entry.getTool(), "n/a"))
                        .append(StringUtils.isBlank(entry.getArtifactId()) ? "" : " artifactId=" + entry.getArtifactId()));
        text.append("\nNeed exact older context: call context_recall with list/search/get for artifactId values.");
        return StringUtils.abbreviate(text.toString(), positive(contextProperties().getSummaryMaxChars(), 6000));
    }

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

    private String renderArtifactReference(ContextArtifact artifact) {
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

    private DynamicTextEntry systemNote(String title, String content) {
        return DynamicTextEntry.builder()
                .entryId(UUID.randomUUID().toString())
                .step(0)
                .role(DynamicTextRole.SYSTEM_NOTE)
                .sourceNode("context_window_manager")
                .title(title)
                .content(content)
                .originalChars(StringUtils.length(content))
                .renderChars(StringUtils.length(content))
                .compacted(true)
                .summary(content)
                .build();
    }

    private int estimateTokens(AgentContext context) {
        if (context == null) {
            return 0;
        }
        int chars = StringUtils.length(context.getQuestion())
                + StringUtils.length(context.getDynamicText() == null ? "" : context.getDynamicText().render())
                + (context.getPlan() == null ? 0 : StringUtils.length(context.getPlan().render()))
                + context.getToolSpecs().stream().mapToInt(spec -> StringUtils.length(spec.getName())
                + StringUtils.length(spec.getDescription()) + StringUtils.length(spec.getInputSchema())).sum();
        int charsPerToken = positive(properties.getBudget().getEstimatedCharsPerToken(), 4);
        return Math.max(1, chars / charsPerToken);
    }

    private int artifactCount(AgentContext context) {
        if (context == null || StringUtils.isBlank(context.getRootRunId())) {
            return 0;
        }
        return artifactRepository.listByRootRunId(context.getRootRunId()).size();
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

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

}
