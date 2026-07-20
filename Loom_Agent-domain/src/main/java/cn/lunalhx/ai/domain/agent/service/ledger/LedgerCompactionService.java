package cn.lunalhx.ai.domain.agent.service.ledger;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedger;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedgerEntry;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.valobj.LedgerStableType;
import cn.lunalhx.ai.domain.agent.model.valobj.context.ContextArtifactKind;
import cn.lunalhx.ai.domain.agent.service.context.ContextArtifactPurgeService;
import cn.lunalhx.ai.domain.agent.service.context.DeepContextSummaryService;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * Ledger compaction service — triggers when the ledger entry count
 * exceeds the high watermark and compacts down to the low watermark.
 *
 * <h3>Compaction flow</h3>
 * <ol>
 *   <li>Capture all current ledger entries as a transcript artifact.</li>
 *   <li>Build a summary (deterministic or deep-summary with fallback).</li>
 *   <li>Replace ledger entries: [summary entry] + [most recent low-1 entries].</li>
 *   <li>Bump generation, record {@code lastCompactionGeneration},
 *       set {@code ledgerBaselineArtifactId}.</li>
 * </ol>
 *
 * <h3>Watermark guard</h3>
 * <p>After compaction, the entry count is ≤ low watermark &lt; high watermark,
 * so the check naturally prevents re-entry until enough new entries accumulate.
 *
 * <h3>Generation semantics</h3>
 * <p>Each compaction increments {@code generation} by 1. Summary is frozen
 * for the new generation; it cannot be rewritten until the next compaction
 * (which creates yet another generation).
 *
 * <h3>Deep summary fallback</h3>
 * <p>Try deep (LLM) summary first if {@code DeepContextSummaryService} is
 * available. On any failure, fall back to deterministic summary. The strategy
 * name reflects the final approach: {@code "deep_summary"},
 * {@code "deep_summary_deterministic"}, or {@code "deterministic"}.
 */
public final class LedgerCompactionService {

    private static final Logger log = LoggerFactory.getLogger(LedgerCompactionService.class);

    private final LedgerWatermark watermark;
    private final ContextArtifactRepository artifactRepository;
    private final ContextBlobStore blobStore;
    private final DeepContextSummaryService deepSummaryService;
    private final int maxCompactionDepth;
    private final ContextArtifactPurgeService purgeService;
    private final LedgerTranscriptRenderer renderer;
    private final ToIntFunction<AgentContext> tokenEstimator;
    private final int autoCompactTokenLimit;
    private final List<BeforeCompactionHook> beforeCompactionHooks;

    public LedgerCompactionService(LedgerWatermark watermark,
                                    ContextArtifactRepository artifactRepository,
                                    ContextBlobStore blobStore,
                                    DeepContextSummaryService deepSummaryService,
                                    ContextArtifactPurgeService purgeService) {
        this(watermark, artifactRepository, blobStore, deepSummaryService, purgeService, 3);
    }

    public LedgerCompactionService(LedgerWatermark watermark,
                                    ContextArtifactRepository artifactRepository,
                                    ContextBlobStore blobStore,
                                    DeepContextSummaryService deepSummaryService,
                                    ContextArtifactPurgeService purgeService,
                                    int maxCompactionDepth) {
        this(watermark, artifactRepository, blobStore, deepSummaryService, purgeService, maxCompactionDepth, null, 0);
    }

    public LedgerCompactionService(LedgerWatermark watermark,
                                    ContextArtifactRepository artifactRepository,
                                    ContextBlobStore blobStore,
                                    DeepContextSummaryService deepSummaryService,
                                    ContextArtifactPurgeService purgeService,
                                    int maxCompactionDepth,
                                    ToIntFunction<AgentContext> tokenEstimator,
                                    int autoCompactTokenLimit) {
        this(watermark, artifactRepository, blobStore, deepSummaryService, purgeService,
                maxCompactionDepth, tokenEstimator, autoCompactTokenLimit, List.of());
    }

    public LedgerCompactionService(LedgerWatermark watermark,
                                    ContextArtifactRepository artifactRepository,
                                    ContextBlobStore blobStore,
                                    DeepContextSummaryService deepSummaryService,
                                    ContextArtifactPurgeService purgeService,
                                    int maxCompactionDepth,
                                    ToIntFunction<AgentContext> tokenEstimator,
                                    int autoCompactTokenLimit,
                                    List<BeforeCompactionHook> beforeCompactionHooks) {
        this.watermark = Objects.requireNonNull(watermark, "watermark must not be null");
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository must not be null");
        this.blobStore = Objects.requireNonNull(blobStore, "blobStore must not be null");
        this.deepSummaryService = deepSummaryService;
        this.purgeService = purgeService;
        this.maxCompactionDepth = maxCompactionDepth;
        this.renderer = new LedgerTranscriptRenderer();
        this.tokenEstimator = tokenEstimator;
        this.autoCompactTokenLimit = autoCompactTokenLimit;
        this.beforeCompactionHooks = beforeCompactionHooks == null
                ? List.of() : List.copyOf(beforeCompactionHooks);
    }

    public LedgerCompactionService(LedgerWatermark watermark,
                                    ContextArtifactRepository artifactRepository,
                                    ContextBlobStore blobStore,
                                    DeepContextSummaryService deepSummaryService) {
        this(watermark, artifactRepository, blobStore, deepSummaryService, null);
    }

    public LedgerCompactionService(LedgerWatermark watermark,
                                    ContextArtifactRepository artifactRepository,
                                    ContextBlobStore blobStore) {
        this(watermark, artifactRepository, blobStore, null, null);
    }

    // ================================================================
    // Public API
    // ================================================================

    /**
     * Compact the ledger if the entry count exceeds the high watermark.
     *
     * @param context the agent context with an active ledger
     * @return compaction result (never null)
     */
    public LedgerCompactionResult compactIfNeeded(AgentContext context) {
        Objects.requireNonNull(context, "context must not be null");

        if (!context.isLedgerReady()) {
            ConversationLedger ledger = context.getConversationLedger();
            int count = ledger != null ? ledger.size() : 0;
            return LedgerCompactionResult.notNeeded(count, context.getGeneration());
        }

        ConversationLedger ledger = context.getConversationLedger();
        if (ledger == null || ledger.isEmpty()) {
            return LedgerCompactionResult.notNeeded(0, context.getGeneration());
        }

        // Micro-compaction: replace old TOOL_RESULT entries that have artifact IDs
        // with stable <persisted-output> references. This runs every prompt cycle
        // and does not bump generation.
        microCompact(context, ledger);

        int entryCount = ledger.size();
        int estimatedTokens = estimateTokens(context);
        boolean tokenTriggered = tokenEstimator != null && autoCompactTokenLimit > 0
                && estimatedTokens > autoCompactTokenLimit;
        boolean entryCountTriggered = entryCount > watermark.highEntryCount();

        if (!tokenTriggered && !entryCountTriggered) {
            return LedgerCompactionResult.notNeeded(entryCount, context.getGeneration(),
                    estimatedTokens, autoCompactTokenLimit);
        }

        return compact(context, estimatedTokens);
    }

    /**
     * Micro-compaction: replace old TOOL_RESULT entries that carry an
     * {@code artifactId} with a stable {@code <persisted-output>} reference.
     *
     * <p>Keeps the most recent {@code keepRecent} tool results intact.
     * Does NOT bump generation — this is a content-level optimization that
     * preserves entry count and sequence numbers.
     *
     * @return true if any entries were modified
     */
    public boolean microCompact(AgentContext context, ConversationLedger ledger) {
        if (ledger == null || ledger.isEmpty()) {
            return false;
        }
        List<ConversationLedgerEntry> entries = new ArrayList<>(ledger.entries());
        int keepRecent = 4; // keep most recent 4 tool results intact
        int seenToolResults = 0;
        boolean changed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            ConversationLedgerEntry entry = entries.get(i);
            if (entry.stableType() != LedgerStableType.TOOL_RESULT) {
                continue;
            }
            seenToolResults++;
            if (seenToolResults <= keepRecent || entry.microCompacted()) {
                continue;
            }
            if (StringUtils.isBlank(entry.artifactId())) {
                continue;
            }
            String reference = "<persisted-output"
                    + " artifactId=\"" + entry.artifactId() + "\""
                    + " kind=\"tool_result\""
                    + " originalChars=\"" + nullToZero(entry.originalChars()) + "\""
                    + " />\n"
                    + "This tool result was compacted."
                    + " Use context_recall get with this artifactId when exact output is needed.";
            entries.set(i, ConversationLedgerEntry.builder()
                    .entryId(entry.entryId())
                    .sequence(entry.sequence())
                    .role(entry.role())
                    .content(reference)
                    .stableType(entry.stableType())
                    .eventKey(entry.eventKey())
                    .toolName(entry.toolName())
                    .artifactId(entry.artifactId())
                    .originalChars(entry.originalChars())
                    .renderChars(reference.length())
                    .microCompacted(true)
                    .compactionDepth(entry.compactionDepth())
                    .snipped(false)
                    .build());
            changed = true;
        }
        if (changed) {
            ledger.replaceEntries(entries);
        }
        return changed;
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private int estimateTokens(AgentContext context) {
        if (tokenEstimator == null) {
            return -1;
        }
        try {
            return tokenEstimator.applyAsInt(context);
        } catch (Exception e) {
            log.warn("Token estimation failed during compaction check: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Force a compaction regardless of watermark.
     *
     * <p>Useful for testing or for explicit compaction triggers
     * (e.g., on checkpoint/segment boundary).
     */
    public LedgerCompactionResult compact(AgentContext context) {
        return compact(context, estimateTokens(context));
    }

    private LedgerCompactionResult compact(AgentContext context, int estimatedTokensBefore) {
        ConversationLedger ledger = context.getConversationLedger();
        if (ledger == null) {
            return LedgerCompactionResult.notNeeded(0, context.getGeneration());
        }

        int beforeCount = ledger.size();
        List<ConversationLedgerEntry> oldEntries = new ArrayList<>(ledger.entries());

        int maxInputDepth = oldEntries.stream()
                .mapToInt(ConversationLedgerEntry::compactionDepth)
                .max().orElse(0);
        int nextDepth = maxInputDepth + 1;
        boolean depthGuarded = nextDepth > maxCompactionDepth;

        TailSelection tailSelection = selectPreservedTail(oldEntries,
                Math.max(1, watermark.lowEntryCount() - 1));
        List<ConversationLedgerEntry> preservedEntries = tailSelection.entries();
        int compactedCount = oldEntries.size() - tailSelection.originalEntryCount();
        List<ConversationLedgerEntry> entriesToCompact = List.copyOf(oldEntries.subList(0, compactedCount));
        invokeBeforeCompactionHooks(context, entriesToCompact, preservedEntries);

        // ---- 1. Persist transcript artifact ----
        String transcript = renderer.render(oldEntries);
        ContextArtifact artifact = persistArtifact(context, transcript);

        // ---- 2. Build summary (deep first, depth-guarded fallback to deterministic) ----
        String strategy;
        String summary;
        if (depthGuarded) {
            summary = buildDeterministicSummary(context, oldEntries, artifact,
                    nextDepth, maxInputDepth, maxCompactionDepth);
            strategy = "deterministic_depth_guarded";
        } else {
            try {
                String deepResult = tryDeepSummary(context, oldEntries, artifact, transcript,
                        nextDepth, maxInputDepth, maxCompactionDepth);
                strategy = "deep_summary";
                summary = deepResult;
            } catch (Exception e) {
                summary = buildDeterministicSummary(context, oldEntries, artifact,
                        nextDepth, maxInputDepth, maxCompactionDepth);
                strategy = deepSummaryService != null ? "deep_summary_deterministic" : "deterministic";
            }
        }

        // ---- 3. Build new entry list: recent tail + summary ----
        // Summary goes LAST so sequence numbers are monotonic across the list.
        List<ConversationLedgerEntry> newEntries = new ArrayList<>(preservedEntries);
        newEntries.add(createSummaryEntry(ledger.nextSequence(), artifact.getArtifactId(), nextDepth,
                context.getGeneration() + 1));

        // ---- 4. Replace ledger entries ----
        ledger.replaceEntries(newEntries);

        // ---- 5. Bump generation and record compaction ----
        int newGen = context.incrementGeneration();
        context.setLastCompactionGeneration(newGen);
        context.setLedgerBaselineArtifactId(artifact.getArtifactId());
        context.setContextTranscriptArtifactId(artifact.getArtifactId());

        // ---- 5b. Record incremental summary state for next compaction (TODO7 Phase 2) ----
        context.setContextSummaryText(summary);
        long summarizedThroughSequence = oldEntries.stream()
                .mapToLong(ConversationLedgerEntry::sequence)
                .max().orElse(-1L);
        context.setContextSummaryThroughSequence(summarizedThroughSequence);

        // ---- 6. Purge obsolete TRANSCRIPT artifacts ----
        purgeObsoleteTranscripts(context, artifact.getArtifactId());

        return LedgerCompactionResult.compactedWithTokens(newGen, beforeCount, newEntries.size(),
                strategy, artifact.getArtifactId(), nextDepth, maxInputDepth,
                maxCompactionDepth, depthGuarded, estimatedTokensBefore, autoCompactTokenLimit);
    }

    /**
     * Check whether the next compaction would exceed the max compaction depth.
     *
     * <p>Used by {@code DeepSummaryStep} to decide whether to skip compaction
     * entirely rather than produce yet another deterministic fallback summary.
     */
    public boolean wouldExceedMaxCompactionDepth(AgentContext context) {
        ConversationLedger ledger = context.getConversationLedger();
        if (ledger == null || ledger.isEmpty()) {
            return false;
        }
        int maxInputDepth = ledger.entries().stream()
                .mapToInt(ConversationLedgerEntry::compactionDepth)
                .max().orElse(0);
        return maxInputDepth + 1 > maxCompactionDepth;
    }

    // ================================================================
    // Internal: summary building
    // ================================================================

    private String buildDeterministicSummary(AgentContext context,
                                             List<ConversationLedgerEntry> oldEntries,
                                             ContextArtifact artifact,
                                             int compactionDepth, int maxInputCompactionDepth,
                                             int maxAllowedCompactionDepth) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Ledger Compaction Summary]\n");
        sb.append("CompactionDepth: ").append(compactionDepth).append('\n');
        sb.append("MaxInputCompactionDepth: ").append(maxInputCompactionDepth).append('\n');
        sb.append("MaxAllowedCompactionDepth: ").append(maxAllowedCompactionDepth).append('\n');
        sb.append("UserGoal: ").append(StringUtils.abbreviate(
                StringUtils.defaultString(context.getQuestion()), 800)).append('\n');
        if (context.getPlan() != null) {
            sb.append("CurrentPlan:\n").append(StringUtils.abbreviate(
                    context.getPlan().render(), 4000)).append('\n');
        }
        sb.append("Generation: ").append(context.getGeneration() + 1).append('\n');
        sb.append("CompactedTranscriptArtifactId: ").append(artifact.getArtifactId()).append('\n');
        sb.append("EntriesBeforeCompaction: ").append(oldEntries.size()).append('\n');

        if (StringUtils.isNotBlank(context.getContextSummaryText())) {
            sb.append("\n[Previous Summary]\n");
            sb.append(context.getContextSummaryText());
            sb.append("\n");
        }

        // Summarize recent tool/assistant entries
        List<ConversationLedgerEntry> recent = oldEntries.stream()
                .filter(e -> e.stableType() == LedgerStableType.ASSISTANT_ACTION
                        || e.stableType() == LedgerStableType.TOOL_RESULT)
                .toList();
        sb.append("RecentActions:\n");
        int recentShow = Math.min(12, recent.size());
        int recentStart = recent.size() - recentShow;
        for (int i = recentStart; i < recent.size(); i++) {
            ConversationLedgerEntry e = recent.get(i);
            sb.append("  [").append(e.sequence()).append("] ")
                    .append(e.role()).append(" (").append(e.stableType().code()).append(")");
            if (e.eventKey() != null) {
                sb.append(" key=").append(e.eventKey());
            }
            sb.append('\n');
        }
        sb.append("\nNeed exact older context: call context_recall with action=get, artifactId=")
                .append(artifact.getArtifactId());
        return sb.toString();
    }

    /**
     * Attempt deep (LLM-driven) summary. Throws on any failure —
     * caller falls back to deterministic.
     */
    private String tryDeepSummary(AgentContext context,
                                  List<ConversationLedgerEntry> oldEntries,
                                  ContextArtifact artifact,
                                  String transcript,
                                  int compactionDepth, int maxInputCompactionDepth,
                                  int maxAllowedCompactionDepth) throws Exception {
        if (deepSummaryService == null) {
            throw new IllegalStateException("deep summary service is not available");
        }

        // Check for incremental compaction
        String previousSummary = context.getContextSummaryText();
        long throughSeq = context.getContextSummaryThroughSequence();

        // Filter entries for incremental: only new entries since last compaction,
        // excluding compaction entries
        List<ConversationLedgerEntry> entriesForSummary;
        if (StringUtils.isNotBlank(previousSummary) && throughSeq > 0) {
            entriesForSummary = oldEntries.stream()
                    .filter(e -> e.sequence() > throughSeq)
                    .filter(e -> e.eventKey() == null || !e.eventKey().startsWith("compaction:"))
                    .toList();
        } else {
            entriesForSummary = oldEntries;
        }

        // Convert ledger entries to string transcript entries for the deep summary service
        List<String> transcriptEntries = renderAsTranscriptEntries(entriesForSummary);

        DeepContextSummaryService.DeepSummaryResult result;
        if (StringUtils.isNotBlank(previousSummary) && throughSeq > 0) {
            result = deepSummaryService.summarize(context, transcriptEntries,
                    System.currentTimeMillis() + 30_000L, previousSummary);
        } else {
            result = deepSummaryService.summarize(context, transcriptEntries,
                    System.currentTimeMillis() + 30_000L);
        }

        if (result == null || StringUtils.isBlank(result.getSummary())) {
            throw new IllegalStateException("deep context summary returned empty result");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[Ledger Deep Compaction Summary — Generation ")
                .append(context.getGeneration() + 1).append("]\n");
        sb.append("\nCompactionDepth: ").append(compactionDepth);
        sb.append("\nMaxInputCompactionDepth: ").append(maxInputCompactionDepth);
        sb.append("\nMaxAllowedCompactionDepth: ").append(maxAllowedCompactionDepth);
        sb.append(result.getSummary());
        sb.append("\n\nCompactedTranscriptArtifactId: ").append(artifact.getArtifactId());
        sb.append("\nEntriesBeforeCompaction: ").append(oldEntries.size());
        sb.append("\n\nNeed exact older context: call context_recall with action=get, artifactId=")
                .append(artifact.getArtifactId());
        return sb.toString();
    }

    /** Render ledger entries as a list of strings for the deep summary service. */
    private List<String> renderAsTranscriptEntries(List<ConversationLedgerEntry> entries) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            ConversationLedgerEntry entry = entries.get(i);
            String rendered = renderTranscriptEntry(entry);
            if (entry.stableType() == LedgerStableType.ASSISTANT_ACTION
                    && i + 1 < entries.size()
                    && isToolPair(entry, entries.get(i + 1))) {
                rendered += "\n\n" + renderTranscriptEntry(entries.get(++i));
            }
            result.add(rendered);
        }
        return result;
    }

    private String renderTranscriptEntry(ConversationLedgerEntry entry) {
        return "[" + entry.sequence() + "] " + entry.role()
                + " (" + entry.stableType().code() + "):\n"
                + StringUtils.abbreviate(entry.content(), 4000);
    }

    private TailSelection selectPreservedTail(
            List<ConversationLedgerEntry> entries, int targetEntryCount) {
        List<List<ConversationLedgerEntry>> units = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            ConversationLedgerEntry entry = entries.get(i);
            if (entry.stableType() == LedgerStableType.ASSISTANT_ACTION
                    && i + 1 < entries.size() && isToolPair(entry, entries.get(i + 1))) {
                units.add(List.of(entry, entries.get(++i)));
            } else {
                units.add(List.of(entry));
            }
        }
        List<ConversationLedgerEntry> selected = new ArrayList<>();
        int count = 0;
        int originalEntryCount = 0;
        for (int i = units.size() - 1; i >= 0; i--) {
            List<ConversationLedgerEntry> unit = units.get(i);
            if (selected.isEmpty() && unit.size() > targetEntryCount) {
                selected.add(summarizeOversizedUnit(unit));
                originalEntryCount += unit.size();
                break;
            }
            if (!selected.isEmpty() && count + unit.size() > targetEntryCount) {
                break;
            }
            selected.addAll(0, unit);
            count += unit.size();
            originalEntryCount += unit.size();
        }
        return new TailSelection(List.copyOf(selected), originalEntryCount);
    }

    private record TailSelection(List<ConversationLedgerEntry> entries, int originalEntryCount) {
    }

    private ConversationLedgerEntry summarizeOversizedUnit(List<ConversationLedgerEntry> unit) {
        ConversationLedgerEntry first = unit.getFirst();
        ConversationLedgerEntry last = unit.getLast();
        String correlation = eventCorrelation(first.eventKey());
        String content = "[Atomic tool interaction compacted as one unit"
                + (correlation.isBlank() ? "" : ": " + correlation)
                + ". The complete call and result are stored in the transcript artifact.]";
        return ConversationLedgerEntry.builder()
                .sequence(last.sequence())
                .role("user")
                .content(content)
                .stableType(LedgerStableType.SYSTEM_NOTE)
                .eventKey("pair-summary:" + (correlation.isBlank() ? first.entryId() : correlation))
                .toolName(first.toolName() != null ? first.toolName() : last.toolName())
                .renderChars(content.length())
                .compacted(true)
                .compactionDepth(Math.max(first.compactionDepth(), last.compactionDepth()) + 1)
                .build();
    }

    private boolean isToolPair(ConversationLedgerEntry assistant, ConversationLedgerEntry result) {
        if (result.stableType() != LedgerStableType.TOOL_RESULT) {
            return false;
        }
        return eventCorrelation(assistant.eventKey()).equals(eventCorrelation(result.eventKey()))
                && !eventCorrelation(assistant.eventKey()).isBlank();
    }

    private String eventCorrelation(String eventKey) {
        if (eventKey == null) {
            return "";
        }
        int lastSeparator = eventKey.lastIndexOf(':');
        return lastSeparator < 0 ? "" : eventKey.substring(0, lastSeparator);
    }

    private void invokeBeforeCompactionHooks(AgentContext context,
                                             List<ConversationLedgerEntry> entriesToCompact,
                                             List<ConversationLedgerEntry> preservedEntries) {
        for (BeforeCompactionHook hook : beforeCompactionHooks) {
            try {
                hook.beforeCompaction(context, entriesToCompact, preservedEntries);
            } catch (RuntimeException e) {
                log.warn("Before-compaction hook failed: {}", e.getMessage());
            }
        }
    }

    // ================================================================
    // Internal: artifact persistence
    // ================================================================

    private ContextArtifact persistArtifact(AgentContext context, String content) {
        String artifactId = "ctx-" + UUID.randomUUID();
        String storageUri = blobStore.write(context.getRootRunId(), artifactId, content);
        ContextArtifact artifact = ContextArtifact.builder()
                .artifactId(artifactId)
                .runId(context.getRunId())
                .rootRunId(context.getRootRunId())
                .conversationId(context.getConversationId())
                .kind(ContextArtifactKind.TRANSCRIPT)
                .storageUri(storageUri)
                .preview(StringUtils.abbreviate(content, 2000))
                .sha256(DigestUtils.sha256Hex(content))
                .originalChars(content.length())
                .retainedChars(Math.min(content.length(), 2000))
                .createdAt(Instant.now())
                .build();
        return artifactRepository.save(artifact);
    }

    // ================================================================
    // Internal: entry creation
    // ================================================================

    private ConversationLedgerEntry createSummaryEntry(long sequence, String artifactId,
                                                       int compactionDepth, int generation) {
        return ConversationLedgerEntry.builder()
                .sequence(sequence)
                .role("user")
                .content("[Context compressed to generation " + generation
                        + ". Durable summary is injected separately. Transcript artifactId: "
                        + artifactId + "]")
                .stableType(LedgerStableType.SYSTEM_NOTE)
                .eventKey("compaction:" + artifactId)
                .compactionDepth(compactionDepth)
                .microCompacted(false)
                .build();
    }

    private void purgeObsoleteTranscripts(AgentContext context, String currentArtifactId) {
        if (purgeService == null) {
            return;
        }
        try {
            List<ContextArtifact> candidates;
            String conversationId = context.getConversationId();
            if (conversationId != null && !conversationId.isEmpty()) {
                candidates = artifactRepository.listByConversationIdAndKind(conversationId, ContextArtifactKind.TRANSCRIPT);
            } else {
                candidates = artifactRepository.listByRootRunId(context.getRootRunId()).stream()
                        .filter(a -> a.getKind() == ContextArtifactKind.TRANSCRIPT)
                        .toList();
            }
            List<ContextArtifact> obsolete = candidates.stream()
                    .filter(a -> !a.getArtifactId().equals(currentArtifactId))
                    .toList();
            if (!obsolete.isEmpty()) {
                int purged = purgeService.purgeArtifactsNonFatal(obsolete);
                if (purged > 0) {
                    log.info("Compaction purged {} obsolete TRANSCRIPT artifacts", purged);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to purge obsolete transcripts after compaction: {}", e.getMessage());
        }
    }
}
