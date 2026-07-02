package cn.lunalhx.ai.infrastructure.gateway.diagnostics;

/**
 * 一次缓存诊断的纯数据结果。无副作用、不可变。
 *
 * <p>previews 已脱敏并被截断；raw payload hash 是 SHA-256 十六进制，payload 原文不会出现在本对象中。
 * toString 故意只输出分类与计数，避免把任何可能包含敏感信息的字段写进日志/异常。
 */
public final class CacheDiagnosticResult {

    private final CacheDiagnosticCategory category;
    private final String currentHash;
    private final String previousHash;
    private final int lcpLength;
    private final double lcpRatio;
    private final Integer firstDiffIndex;
    private final String firstDiffRole;
    private final String previousPreview;
    private final String currentPreview;
    private final String currentRawPayloadHash;
    private final String previousRawPayloadHash;
    private final int rawPayloadLcpLength;
    private final boolean toolsIncludedInCurrent;
    private final boolean toolsIncludedInPrevious;
    private final int currentMessageCount;
    private final int previousMessageCount;
    private final String summary;

    public CacheDiagnosticResult(CacheDiagnosticCategory category,
                                 String currentHash,
                                 String previousHash,
                                 int lcpLength,
                                 double lcpRatio,
                                 Integer firstDiffIndex,
                                 String firstDiffRole,
                                 String previousPreview,
                                 String currentPreview,
                                 String currentRawPayloadHash,
                                 String previousRawPayloadHash,
                                 int rawPayloadLcpLength,
                                 boolean toolsIncludedInCurrent,
                                 boolean toolsIncludedInPrevious,
                                 int currentMessageCount,
                                 int previousMessageCount,
                                 String summary) {
        this.category = category;
        this.currentHash = currentHash;
        this.previousHash = previousHash;
        this.lcpLength = lcpLength;
        this.lcpRatio = lcpRatio;
        this.firstDiffIndex = firstDiffIndex;
        this.firstDiffRole = firstDiffRole;
        this.previousPreview = previousPreview;
        this.currentPreview = currentPreview;
        this.currentRawPayloadHash = currentRawPayloadHash;
        this.previousRawPayloadHash = previousRawPayloadHash;
        this.rawPayloadLcpLength = rawPayloadLcpLength;
        this.toolsIncludedInCurrent = toolsIncludedInCurrent;
        this.toolsIncludedInPrevious = toolsIncludedInPrevious;
        this.currentMessageCount = currentMessageCount;
        this.previousMessageCount = previousMessageCount;
        this.summary = summary;
    }

    public CacheDiagnosticCategory category() {
        return category;
    }

    public String currentHash() {
        return currentHash;
    }

    public String previousHash() {
        return previousHash;
    }

    public int lcpLength() {
        return lcpLength;
    }

    public double lcpRatio() {
        return lcpRatio;
    }

    /**
     * 首个差异 message 在 previous 中的下标。
     * - null：表示 previous 与 current 完全一致或无 previous；
     * - 等于 previousMessageCount：表示 previous 是 current 的严格前缀（仅追加）；
     * - 其他值：表示从该下标开始出现差异。
     */
    public Integer firstDiffIndex() {
        return firstDiffIndex;
    }

    public String firstDiffRole() {
        return firstDiffRole;
    }

    public String previousPreview() {
        return previousPreview;
    }

    public String currentPreview() {
        return currentPreview;
    }

    public String currentRawPayloadHash() {
        return currentRawPayloadHash;
    }

    public String previousRawPayloadHash() {
        return previousRawPayloadHash;
    }

    /**
     * -1 表示没有同时提供两个 raw payload。
     */
    public int rawPayloadLcpLength() {
        return rawPayloadLcpLength;
    }

    public boolean toolsIncludedInCurrent() {
        return toolsIncludedInCurrent;
    }

    public boolean toolsIncludedInPrevious() {
        return toolsIncludedInPrevious;
    }

    public int currentMessageCount() {
        return currentMessageCount;
    }

    public int previousMessageCount() {
        return previousMessageCount;
    }

    public String summary() {
        return summary;
    }

    /**
     * 故意只打印分类与计数，不输出任何可能含敏感信息的字段。
     */
    @Override
    public String toString() {
        return "CacheDiagnosticResult{" +
                "category=" + category +
                ", currentMessageCount=" + currentMessageCount +
                ", previousMessageCount=" + previousMessageCount +
                ", lcpLength=" + lcpLength +
                ", firstDiffIndex=" + firstDiffIndex +
                ", toolsIncludedInCurrent=" + toolsIncludedInCurrent +
                ", toolsIncludedInPrevious=" + toolsIncludedInPrevious +
                '}';
    }
}
