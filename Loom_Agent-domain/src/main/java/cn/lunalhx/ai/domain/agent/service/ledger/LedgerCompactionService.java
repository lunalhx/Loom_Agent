package cn.lunalhx.ai.domain.agent.service.ledger;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedger;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedgerEntry;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.valobj.LedgerStableType;
import cn.lunalhx.ai.domain.agent.model.valobj.context.ContextArtifactKind;
import cn.lunalhx.ai.domain.agent.service.context.DeepContextSummaryService;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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

    private final LedgerWatermark watermark;
    private final ContextArtifactRepository artifactRepository;
    private final ContextBlobStore blobStore;
    private final DeepContextSummaryService deepSummaryService;
    private final LedgerTranscriptRenderer renderer;

    /**
     * Full constructor with optional deep-summary support.
     *
     * @param watermark           the high/low entry count thresholds
     * @param artifactRepository  artifact storage
     * @param blobStore           blob storage for transcript content
     * @param deepSummaryService  optional; if null, only deterministic summaries are used
     */
    public LedgerCompactionService(LedgerWatermark watermark,
                                   ContextArtifactRepository artifactRepository,
                                   ContextBlobStore blobStore,
                                   DeepContextSummaryService deepSummaryService) {
        this.watermark = Objects.requireNonNull(watermark, "watermark must not be null");
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository must not be null");
        this.blobStore = Objects.requireNonNull(blobStore, "blobStore must not be null");
        this.deepSummaryService = deepSummaryService;
        this.renderer = new LedgerTranscriptRenderer();
    }

    /** Convenience constructor without deep-summary support. */
    public LedgerCompactionService(LedgerWatermark watermark,
                                   ContextArtifactRepository artifactRepository,
                                   ContextBlobStore blobStore) {
        this(watermark, artifactRepository, blobStore, null);
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
        if (entryCount <= watermark.highEntryCount()) {
            return LedgerCompactionResult.notNeeded(entryCount, context.getGeneration());
        }

        return compact(context);
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
            if (seenToolResults <= keepRecent || entry.compacted()) {
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
                    .compacted(true)
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

    /**
     * Force a compaction regardless of watermark.
     *
     * <p>Useful for testing or for explicit compaction triggers
     * (e.g., on checkpoint/segment boundary).
     */
    public LedgerCompactionResult compact(AgentContext context) {
        ConversationLedger ledger = context.getConversationLedger();
        if (ledger == null) {
            return LedgerCompactionResult.notNeeded(0, context.getGeneration());
        }

        int beforeCount = ledger.size();
        List<ConversationLedgerEntry> oldEntries = new ArrayList<>(ledger.entries());

        // ---- 1. Persist transcript artifact ----
        String transcript = renderer.render(oldEntries);
        ContextArtifact artifact = persistArtifact(context, transcript);

        // ---- 2. Build summary (deep first, fallback to deterministic) ----
        String strategy;
        String summary;
        try {
            String deepResult = tryDeepSummary(context, oldEntries, artifact, transcript);
            strategy = "deep_summary";
            summary = deepResult;
        } catch (Exception e) {
            // deep summary unavailable / failed — deterministic fallback
            summary = buildDeterministicSummary(context, oldEntries, artifact);
            strategy = deepSummaryService != null ? "deep_summary_deterministic" : "deterministic";
        }

        // ---- 3. Build new entry list: recent tail + summary ----
        // Summary goes LAST so sequence numbers are monotonic across the list.
        int keepRecent = Math.max(1, watermark.lowEntryCount() - 1); // -1 for summary entry
        int start = Math.max(0, oldEntries.size() - keepRecent);

        List<ConversationLedgerEntry> newEntries = new ArrayList<>();
        for (int i = start; i < oldEntries.size(); i++) {
            newEntries.add(oldEntries.get(i));
        }
        newEntries.add(createSummaryEntry(ledger.nextSequence(), summary, artifact.getArtifactId()));

        // ---- 4. Replace ledger entries ----
        ledger.replaceEntries(newEntries);

        // ---- 5. Bump generation and record compaction ----
        int newGen = context.incrementGeneration();
        context.setLastCompactionGeneration(newGen);
        context.setLedgerBaselineArtifactId(artifact.getArtifactId());
        // Mirror to the recovery transcript field so resetContextRecovery can
        // distinguish a ledger-compaction artifact from a recovery artifact.
        context.setContextTranscriptArtifactId(artifact.getArtifactId());

        return LedgerCompactionResult.compacted(newGen, beforeCount, newEntries.size(),
                strategy, artifact.getArtifactId());
    }

    // ================================================================
    // Internal: summary building
    // ================================================================

    private String buildDeterministicSummary(AgentContext context,
                                             List<ConversationLedgerEntry> oldEntries,
                                             ContextArtifact artifact) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Ledger Compaction Summary]\n");
        sb.append("UserGoal: ").append(StringUtils.abbreviate(
                StringUtils.defaultString(context.getQuestion()), 800)).append('\n');
        if (context.getPlan() != null) {
            sb.append("CurrentPlan:\n").append(StringUtils.abbreviate(
                    context.getPlan().render(), 4000)).append('\n');
        }
        sb.append("Generation: ").append(context.getGeneration() + 1).append('\n');
        sb.append("CompactedTranscriptArtifactId: ").append(artifact.getArtifactId()).append('\n');
        sb.append("EntriesBeforeCompaction: ").append(oldEntries.size()).append('\n');

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
                                  String transcript) throws Exception {
        if (deepSummaryService == null) {
            throw new IllegalStateException("deep summary service is not available");
        }

        // Convert ledger entries to string transcript entries for the deep summary service
        List<String> transcriptEntries = renderAsTranscriptEntries(oldEntries);

        DeepContextSummaryService.DeepSummaryResult result =
                deepSummaryService.summarize(context, transcriptEntries,
                        System.currentTimeMillis() + 30_000L);

        if (result == null || StringUtils.isBlank(result.getSummary())) {
            throw new IllegalStateException("deep context summary returned empty result");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[Ledger Deep Compaction Summary — Generation ")
                .append(context.getGeneration() + 1).append("]\n");
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
        for (ConversationLedgerEntry e : entries) {
            result.add("[" + e.sequence() + "] " + e.role()
                    + " (" + e.stableType().code() + "):\n"
                    + StringUtils.abbreviate(e.content(), 4000));
        }
        return result;
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

    private ConversationLedgerEntry createSummaryEntry(long sequence, String summary,
                                                       String artifactId) {
        return ConversationLedgerEntry.builder()
                .sequence(sequence)
                .role("user")
                .content(summary)
                .stableType(LedgerStableType.SYSTEM_NOTE)
                .eventKey("compaction:" + artifactId)
                .build();
    }
}
