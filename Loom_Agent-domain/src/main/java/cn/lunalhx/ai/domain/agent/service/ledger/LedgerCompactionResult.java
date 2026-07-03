package cn.lunalhx.ai.domain.agent.service.ledger;

/**
 * Result of a ledger compaction operation.
 *
 * @param compacted          whether compaction was performed
 * @param generation         the new generation created by this compaction (unchanged if not compacted)
 * @param beforeEntryCount   ledger entry count before compaction
 * @param afterEntryCount    ledger entry count after compaction
 * @param strategy           compaction strategy used ("deterministic", "deep_summary", "deep_summary_deterministic")
 * @param transcriptArtifactId artifact ID of the saved pre-compaction transcript
 */
public record LedgerCompactionResult(
        boolean compacted,
        int generation,
        int beforeEntryCount,
        int afterEntryCount,
        String strategy,
        String transcriptArtifactId) {

    public static LedgerCompactionResult notNeeded(int entryCount, int generation) {
        return new LedgerCompactionResult(false, generation, entryCount, entryCount, null, null);
    }

    public static LedgerCompactionResult compacted(int generation, int before, int after,
                                                   String strategy, String transcriptArtifactId) {
        return new LedgerCompactionResult(true, generation, before, after, strategy, transcriptArtifactId);
    }
}
