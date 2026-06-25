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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class ContextWindowManager {

    private final AgentRuntimeProperties properties;
    private final ContextArtifactRepository artifactRepository;
    private final ContextBlobStore blobStore;
    private final DeepContextSummaryService deepSummaryService;

    public ContextWindowManager(AgentRuntimeProperties properties,
                                ContextArtifactRepository artifactRepository,
                                ContextBlobStore blobStore) {
        this(properties, artifactRepository, blobStore, null);
    }

    public ContextWindowManager(AgentRuntimeProperties properties,
                                ContextArtifactRepository artifactRepository,
                                ContextBlobStore blobStore,
                                DeepContextSummaryService deepSummaryService) {
        this.properties = properties;
        this.artifactRepository = artifactRepository;
        this.blobStore = blobStore;
        this.deepSummaryService = deepSummaryService;
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
        if (snipCompact(entries)) {
            strategies.add("snip");
        }
        if (microCompact(entries)) {
            strategies.add("micro");
        }
        context.getDynamicText().replaceEntries(entries);

        int targetTokens = positive(contextProperties().getAutoCompactTokenLimit(), 64000);
        int afterTokens = estimateTokens(context);
        ContextArtifact transcriptArtifact = null;
        if (afterTokens > targetTokens) {
            transcriptArtifact = summaryCompact(context);
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
                .targetTokens(targetTokens)
                .fitsTarget(afterTokens <= targetTokens)
                .retainedEntryCount(context.getDynamicText().entries().size())
                .transcriptArtifactId(transcriptArtifact == null ? null : transcriptArtifact.getArtifactId())
                .build();
    }

    public ContextCompactResult reactiveCompact(AgentContext context) {
        return reactiveCompact(context, positive(contextProperties().getAutoCompactTokenLimit(), 64000));
    }

    public ContextCompactResult reactiveCompact(AgentContext context, int targetTokens) {
        int beforeTokens = estimateTokens(context);
        if (!enabled() || context == null || context.getDynamicText() == null || context.getDynamicText().isEmpty()) {
            return compactResult(false, beforeTokens, beforeTokens, 0, List.of(),
                    targetTokens, 0, context == null ? null : context.getContextTranscriptArtifactId());
        }
        int beforeArtifacts = artifactCount(context);
        List<DynamicTextEntry> entries = new ArrayList<>(context.getDynamicText().entries());
        ContextArtifact transcriptArtifact = ensureTranscript(context, renderEntries(entries));
        int retained = replaceWithSummary(context, entries,
                summaryText(context, entries, transcriptArtifact, summaryCharsForTarget(targetTokens)),
                "Reactive Context Compact",
                positive(contextProperties().getReactiveKeepRecentEntries(), 5),
                targetTokens);
        int afterTokens = estimateTokens(context);
        return compactResult(true, beforeTokens, afterTokens,
                Math.max(0, artifactCount(context) - beforeArtifacts),
                List.of("reactive_summary"), targetTokens, retained, transcriptArtifact.getArtifactId());
    }

    public ContextCompactResult deepSummaryCompact(AgentContext context,
                                                   int targetTokens,
                                                   long deadlineEpochMs) {
        int beforeTokens = estimateTokens(context);
        if (!enabled() || context == null || context.getDynamicText() == null || context.getDynamicText().isEmpty()) {
            return compactResult(false, beforeTokens, beforeTokens, 0, List.of(),
                    targetTokens, 0, context == null ? null : context.getContextTranscriptArtifactId());
        }
        int beforeArtifacts = artifactCount(context);
        List<DynamicTextEntry> entries = new ArrayList<>(context.getDynamicText().entries());
        ContextArtifact transcriptArtifact = ensureTranscript(context, renderEntries(entries));
        String transcript = blobStore.read(transcriptArtifact.getStorageUri());
        String summary;
        String strategy;
        try {
            DeepContextSummaryService.DeepSummaryResult result = deepSummaryService == null
                    ? null
                    : deepSummaryService.summarize(context, transcriptEntries(transcript), deadlineEpochMs);
            if (result == null || StringUtils.isBlank(result.getSummary())) {
                throw new IllegalStateException("deep context summary is unavailable");
            }
            summary = StringUtils.abbreviate(result.getSummary(), summaryCharsForTarget(targetTokens));
            strategy = "deep_summary";
        } catch (RuntimeException e) {
            summary = summaryText(context, entries, transcriptArtifact, summaryCharsForTarget(targetTokens));
            strategy = "deep_summary_deterministic";
        }
        int retained = replaceWithSummary(context, entries, summary, "Deep Context Summary",
                positive(contextProperties().getReactiveKeepRecentEntries(), 5), targetTokens);
        int afterTokens = estimateTokens(context);
        return compactResult(true, beforeTokens, afterTokens,
                Math.max(0, artifactCount(context) - beforeArtifacts),
                List.of(strategy), targetTokens, retained, transcriptArtifact.getArtifactId());
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

    private ContextArtifact summaryCompact(AgentContext context) {
        List<DynamicTextEntry> entries = new ArrayList<>(context.getDynamicText().entries());
        ContextArtifact transcriptArtifact = ensureTranscript(context, renderEntries(entries));

        List<DynamicTextEntry> next = new ArrayList<>();
        entries.stream()
                .filter(entry -> entry.getRole() == DynamicTextRole.USER_TASK)
                .findFirst()
                .ifPresent(next::add);
        next.add(systemNote("Context Summary Compact",
                summaryText(context, entries, transcriptArtifact,
                        positive(contextProperties().getSummaryMaxChars(), 6000))));

        int keepRecent = Math.max(4, positive(contextProperties().getKeepRecentToolResults(), 4) * 2);
        int start = Math.max(0, entries.size() - keepRecent);
        for (DynamicTextEntry entry : entries.subList(start, entries.size())) {
            if (entry.getRole() != DynamicTextRole.USER_TASK) {
                next.add(entry);
            }
        }
        context.getDynamicText().replaceEntries(next);
        return transcriptArtifact;
    }

    private String summaryText(AgentContext context,
                               List<DynamicTextEntry> entries,
                               ContextArtifact transcriptArtifact,
                               int maxChars) {
        StringBuilder text = new StringBuilder();
        text.append("UserGoal: ").append(StringUtils.abbreviate(StringUtils.defaultString(context.getQuestion()), 800)).append('\n');
        if (context.getPlan() != null) {
            text.append("CurrentPlan:\n").append(StringUtils.abbreviate(context.getPlan().render(),
                    Math.max(256, maxChars / 2))).append('\n');
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
        return StringUtils.abbreviate(text.toString(), Math.max(256, maxChars));
    }

    private int replaceWithSummary(AgentContext context,
                                   List<DynamicTextEntry> entries,
                                   String summary,
                                   String title,
                                   int keepRecent,
                                   int targetTokens) {
        List<DynamicTextEntry> next = new ArrayList<>();
        entries.stream()
                .filter(entry -> entry.getRole() == DynamicTextRole.USER_TASK)
                .findFirst()
                .ifPresent(next::add);
        next.add(systemNote(title, summary));

        List<DynamicTextEntry> candidates = entries.stream()
                .filter(entry -> entry.getRole() != DynamicTextRole.USER_TASK)
                .toList();
        int start = Math.max(0, candidates.size() - Math.max(0, keepRecent));
        List<DynamicTextEntry> recent = new ArrayList<>(candidates.subList(start, candidates.size()));
        int maxEntryChars = Math.max(512,
                targetTokens * positive(properties.getBudget().getEstimatedCharsPerToken(), 4)
                        / Math.max(1, keepRecent + 2));
        for (DynamicTextEntry entry : recent) {
            next.add(sizeEntry(context, entry, maxEntryChars));
        }
        context.getDynamicText().replaceEntries(next);
        return recent.size();
    }

    private DynamicTextEntry sizeEntry(AgentContext context, DynamicTextEntry entry, int maxChars) {
        String rendered = renderEntry(entry);
        if (rendered.length() <= maxChars) {
            return entry;
        }
        ContextArtifact artifact = persist(context, ContextArtifactKind.CONTEXT_ENTRY, rendered,
                Math.min(positive(contextProperties().getToolPreviewChars(), 2000), Math.max(128, maxChars / 2)));
        String content = renderArtifactReference(artifact);
        return DynamicTextEntry.builder()
                .entryId(entry.getEntryId())
                .step(entry.getStep())
                .role(entry.getRole())
                .sourceNode(entry.getSourceNode())
                .title(entry.getTitle())
                .tool(entry.getTool())
                .content(content)
                .artifactId(artifact.getArtifactId())
                .originalChars(artifact.getOriginalChars())
                .renderChars(content.length())
                .compacted(true)
                .summary(content)
                .build();
    }

    private ContextArtifact ensureTranscript(AgentContext context, String transcript) {
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

    private List<String> transcriptEntries(String transcript) {
        if (StringUtils.isBlank(transcript)) {
            return List.of();
        }
        return Arrays.stream(transcript.split("\\n\\n(?=## )"))
                .filter(StringUtils::isNotBlank)
                .toList();
    }

    private String renderEntries(List<DynamicTextEntry> entries) {
        return entries.stream().map(this::renderEntry).collect(Collectors.joining("\n\n"));
    }

    private String renderEntry(DynamicTextEntry entry) {
        StringBuilder text = new StringBuilder("## ");
        if (entry.getStep() > 0) {
            text.append("Step ").append(entry.getStep()).append(" - ");
        }
        text.append(entry.getRole().code()).append(" - ").append(entry.getTitle()).append('\n');
        text.append("SourceNode: ").append(entry.getSourceNode()).append('\n');
        if (StringUtils.isNotBlank(entry.getTool())) {
            text.append("Tool: ").append(entry.getTool()).append('\n');
        }
        if (StringUtils.isNotBlank(entry.getInput())) {
            text.append("Input: ").append(entry.getInput()).append('\n');
        }
        text.append(StringUtils.defaultString(entry.getContent()));
        return text.toString();
    }

    private ContextCompactResult compactResult(boolean compacted,
                                               int beforeTokens,
                                               int afterTokens,
                                               int artifactCount,
                                               List<String> strategies,
                                               int targetTokens,
                                               int retainedEntries,
                                               String transcriptArtifactId) {
        return ContextCompactResult.builder()
                .compacted(compacted)
                .beforeEstimatedTokens(beforeTokens)
                .afterEstimatedTokens(afterTokens)
                .artifactCount(artifactCount)
                .strategies(strategies)
                .targetTokens(Math.max(1, targetTokens))
                .fitsTarget(afterTokens <= Math.max(1, targetTokens))
                .retainedEntryCount(retainedEntries)
                .transcriptArtifactId(transcriptArtifactId)
                .build();
    }

    private int summaryCharsForTarget(int targetTokens) {
        int configured = positive(contextProperties().getSummaryMaxChars(), 6000);
        int targetChars = Math.max(1024,
                targetTokens * positive(properties.getBudget().getEstimatedCharsPerToken(), 4) / 3);
        return Math.min(configured, targetChars);
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

    public int estimateTokens(AgentContext context) {
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
