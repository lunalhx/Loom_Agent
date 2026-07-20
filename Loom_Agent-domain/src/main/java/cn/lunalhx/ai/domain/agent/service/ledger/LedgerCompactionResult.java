package cn.lunalhx.ai.domain.agent.service.ledger;

/**
 * Result of a ledger compaction operation.
 *
 * @param compacted          whether compaction was performed
 * @param generation         the new generation created by this compaction (unchanged if not compacted)
 * @param beforeEntryCount   ledger entry count before compaction
 * @param afterEntryCount    ledger entry count after compaction
 * @param strategy                    compaction strategy used ("deterministic", "deep_summary", "deep_summary_deterministic")
 * @param transcriptArtifactId        artifact ID of the saved pre-compaction transcript
 * @param compactionDepth             the compaction depth of the summary entry (1-indexed)
 * @param maxInputCompactionDepth      max compaction depth among input entries
 * @param maxAllowedCompactionDepth    configured threshold for deep summary
 * @param depthGuarded                 whether deterministic fallback was triggered by depth guard
 */
public record LedgerCompactionResult(
        boolean compacted,
        int generation,
        int beforeEntryCount,
        int afterEntryCount,
        String strategy,
        String transcriptArtifactId,
        int compactionDepth,
        int maxInputCompactionDepth,
        int maxAllowedCompactionDepth,
        boolean depthGuarded,
        int estimatedTokens,
        int tokenLimit) {

    public static LedgerCompactionResult notNeeded(int entryCount, int generation) {
        return new LedgerCompactionResult(false, generation, entryCount, entryCount, null, null, 0, 0, 0, false, -1, -1);
    }

    public static LedgerCompactionResult notNeeded(int entryCount, int generation,
                                                    int estimatedTokens, int tokenLimit) {
        return new LedgerCompactionResult(false, generation, entryCount, entryCount,
                null, null, 0, 0, 0, false, estimatedTokens, tokenLimit);
    }

    public static LedgerCompactionResult compacted(int generation, int before, int after,
                                                    String strategy, String transcriptArtifactId) {
        return new LedgerCompactionResult(true, generation, before, after, strategy, transcriptArtifactId, 0, 0, 0, false, -1, -1);
    }

    public static LedgerCompactionResult compactedWithDepth(int generation, int before, int after,
                                                             String strategy, String transcriptArtifactId,
                                                             int compactionDepth, int maxInputCompactionDepth,
                                                             int maxAllowedCompactionDepth, boolean depthGuarded) {
        return new LedgerCompactionResult(true, generation, before, after, strategy,
                transcriptArtifactId, compactionDepth, maxInputCompactionDepth,
                maxAllowedCompactionDepth, depthGuarded, -1, -1);
    }

    public static LedgerCompactionResult compactedWithTokens(int generation, int before, int after,
            String strategy, String transcriptArtifactId, int compactionDepth,
            int maxInputCompactionDepth, int maxAllowedCompactionDepth, boolean depthGuarded,
            int estimatedTokens, int tokenLimit) {
        return new LedgerCompactionResult(true, generation, before, after, strategy,
                transcriptArtifactId, compactionDepth, maxInputCompactionDepth,
                maxAllowedCompactionDepth, depthGuarded, estimatedTokens, tokenLimit);
    }
}
