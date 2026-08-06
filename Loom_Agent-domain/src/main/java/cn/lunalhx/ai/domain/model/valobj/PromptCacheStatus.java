package cn.lunalhx.ai.domain.model.valobj;

/**
 * 三态 prompt-cache 命中语义。
 *
 * <p>{@code HIT} 为 provider 明确报告 cached read tokens 大于 0；
 * {@code MISS} 为 provider 明确支持缓存且报告 0 cached tokens；
 * {@code UNKNOWN} 为 provider 未提供判定字段（含不支持缓存的 provider）。
 */
public enum PromptCacheStatus {

    HIT,
    MISS,
    UNKNOWN;

    public static PromptCacheStatus fromUsage(TokenUsage usage) {
        if (usage == null) {
            return UNKNOWN;
        }
        Integer hit = usage.getPromptCacheHitTokens();
        if (hit != null && hit > 0) {
            return HIT;
        }
        if (hit != null) {
            return MISS;
        }
        return UNKNOWN;
    }

}
