package cn.lunalhx.ai.domain.agent.service.ledger;

/**
 * Watermark configuration for ledger compaction.
 *
 * <p>Compaction triggers when the ledger entry count exceeds {@link #highEntryCount()}
 * and compacts down to {@link #lowEntryCount()} entries (including the summary entry).
 *
 * <p>Invariants: {@code lowEntryCount > 0 && highEntryCount > lowEntryCount}.
 */
public record LedgerWatermark(int highEntryCount, int lowEntryCount) {

    public static final int DEFAULT_HIGH = 200;
    public static final int DEFAULT_LOW = 50;

    public LedgerWatermark {
        if (highEntryCount <= 0) {
            throw new IllegalArgumentException("highEntryCount must be positive, got " + highEntryCount);
        }
        if (lowEntryCount <= 0) {
            throw new IllegalArgumentException("lowEntryCount must be positive, got " + lowEntryCount);
        }
        if (lowEntryCount >= highEntryCount) {
            throw new IllegalArgumentException(
                    "lowEntryCount (" + lowEntryCount + ") must be less than highEntryCount (" + highEntryCount + ")");
        }
    }

    /** Default watermark: high=200, low=50. */
    public static LedgerWatermark defaults() {
        return new LedgerWatermark(DEFAULT_HIGH, DEFAULT_LOW);
    }

    /** Create from nullable Integer config values, falling back to defaults. */
    public static LedgerWatermark fromConfig(Integer high, Integer low) {
        int h = (high != null && high > 0) ? high : DEFAULT_HIGH;
        int l = (low != null && low > 0) ? low : DEFAULT_LOW;
        if (l >= h) {
            // Safety: if config is inconsistent, fall back to defaults
            return defaults();
        }
        return new LedgerWatermark(h, l);
    }
}
