package cn.lunalhx.ai.infrastructure.gateway.diagnostics;

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * Prompt 缓存诊断核心。
 *
 * <p>职责：
 * <ul>
 *   <li>对 canonical messages 计算稳定 SHA-256；</li>
 *   <li>比较前后两次 canonical messages：字符 LCP、首个差异 message、对应 role、脱敏预览；</li>
 *   <li>对最终序列化 payload 提供 raw payload hash 和字符 LCP；</li>
 *   <li>脱敏 Bearer / api-key / token / secret / password / 常见 env 变量赋值；</li>
 *   <li>限制预览和 raw payload 的最大长度；</li>
 *   <li>分类为 APPEND_ONLY_OK / EARLY_PREFIX_DRIFT / TOOLS_CHANGED / HISTORY_REWRITTEN /
 *       COMPACTION_RESET / UNKNOWN，辅以 FIRST_REQUEST / IDENTICAL。</li>
 * </ul>
 *
 * <p>本类无网络 IO、不写日志、不修改入参、不持有状态；{@link #diagnose(CacheDiagnosticInput)} 是纯函数。
 * 设计目标：可被网关在请求前后以相同输入做 A/B 对比。
 */
public final class PromptCacheDiagnostics {

    private final SensitiveContentRedactor redactor;
    private final CanonicalMessagesHasher hasher;

    public PromptCacheDiagnostics() {
        this(SensitiveContentRedactor.create(), CanonicalMessagesHasher.create());
    }

    public PromptCacheDiagnostics(SensitiveContentRedactor redactor, CanonicalMessagesHasher hasher) {
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
    }

    public CacheDiagnosticResult diagnose(CacheDiagnosticInput input) {
        Objects.requireNonNull(input, "input");
        List<CanonicalMessage> prev = input.previousMessages();
        List<CanonicalMessage> curr = input.currentMessages() == null ? List.of() : input.currentMessages();

        int previewLimit = input.previewLimit();

        // 1) 计算 canonical hash（永远计算 current；previous 仅在非空时计算）
        String currentHash = hasher.hash(curr);
        String previousHash = (prev == null || prev.isEmpty()) ? null : hasher.hash(prev);

        // 2) 边界：没有 previous → FIRST_REQUEST
        if (prev == null || prev.isEmpty()) {
            return buildFirstRequest(input, curr, currentHash, previewLimit);
        }

        // 3) tools 翻转独立于 message 差异：先于 IDENTICAL 判定，否则会隐藏 tools 变化
        if (input.toolsIncludedInPrevious() != input.toolsIncludedInCurrent()) {
            return buildToolsChanged(input, curr, currentHash, previousHash, previewLimit);
        }

        // 4) 边界：完全相同 → IDENTICAL
        if (currentHash.equals(previousHash)) {
            return buildIdentical(input, currentHash, previousHash, curr, previewLimit);
        }

        // 5) 计算 message 级别首个差异下标
        int firstDiffIndex = firstMessageDiffIndex(prev, curr);
        String firstDiffRole = firstDiffRole(prev, curr, firstDiffIndex);

        // 6) joined 文本字符 LCP（用 joined 形式而非 length-prefixed，避免 count/长度前缀污染 LCP）
        String prevJoined = hasher.joinedForLcp(prev);
        String currJoined = hasher.joinedForLcp(curr);
        int lcpLength = CanonicalMessagesHasher.charLcpLength(prevJoined, currJoined);
        int minLen = Math.min(prevJoined.length(), currJoined.length());
        double lcpRatio = minLen == 0 ? 0d : (double) lcpLength / (double) minLen;

        // 7) raw payload 辅助诊断
        RawPayloadSignals rawSignals = computeRawPayloadSignals(input);

        // 8) 分类（tools 已在前面处理）
        CacheDiagnosticCategory category = classify(prev, curr, firstDiffIndex);

        // 9) 预览（脱敏 + 截断）
        String currentPreview = previewAtDiff(curr, firstDiffIndex, previewLimit);
        String previousPreview = previewAtDiff(prev, firstDiffIndex, previewLimit);

        String summary = buildSummary(category, prev.size(), curr.size(),
                firstDiffIndex, firstDiffRole, lcpRatio, lcpLength,
                input.toolsIncludedInPrevious(), input.toolsIncludedInCurrent());

        return new CacheDiagnosticResult(
                category,
                currentHash,
                previousHash,
                lcpLength,
                lcpRatio,
                firstDiffIndex,
                firstDiffRole,
                previousPreview,
                currentPreview,
                rawSignals.currentHash,
                rawSignals.previousHash,
                rawSignals.lcpLength,
                input.toolsIncludedInCurrent(),
                input.toolsIncludedInPrevious(),
                curr.size(),
                prev.size(),
                summary);
    }

    private CacheDiagnosticResult buildFirstRequest(CacheDiagnosticInput input,
                                                    List<CanonicalMessage> curr,
                                                    String currentHash,
                                                    int previewLimit) {
        RawPayloadSignals rawSignals = computeRawPayloadSignals(input);
        String currentPreview = previewAtDiff(curr, 0, previewLimit);
        String summary = "FIRST_REQUEST: " + curr.size() + " message(s), no previous baseline"
                + rawSignals.summarySuffix();
        return new CacheDiagnosticResult(
                CacheDiagnosticCategory.FIRST_REQUEST,
                currentHash,
                null,
                0,
                0d,
                null,
                null,
                null,
                currentPreview,
                rawSignals.currentHash,
                rawSignals.previousHash,
                rawSignals.lcpLength,
                input.toolsIncludedInCurrent(),
                input.toolsIncludedInPrevious(),
                curr.size(),
                0,
                summary);
    }

    private CacheDiagnosticResult buildIdentical(CacheDiagnosticInput input,
                                                 String currentHash,
                                                 String previousHash,
                                                 List<CanonicalMessage> curr,
                                                 int previewLimit) {
        List<CanonicalMessage> prev = input.previousMessages();
        RawPayloadSignals rawSignals = computeRawPayloadSignals(input);
        String currentPreview = previewAtDiff(curr, 0, previewLimit);
        String prevJoined = hasher.joinedForLcp(prev);
        String currJoined = hasher.joinedForLcp(curr);
        int lcpLength = CanonicalMessagesHasher.charLcpLength(prevJoined, currJoined);
        int minLen = Math.min(prevJoined.length(), currJoined.length());
        double lcpRatio = minLen == 0 ? 0d : 1d;
        String summary = "IDENTICAL: " + curr.size() + " message(s), canonical hash unchanged"
                + rawSignals.summarySuffix();
        return new CacheDiagnosticResult(
                CacheDiagnosticCategory.IDENTICAL,
                currentHash,
                previousHash,
                lcpLength,
                lcpRatio,
                null,
                null,
                null,
                currentPreview,
                rawSignals.currentHash,
                rawSignals.previousHash,
                rawSignals.lcpLength,
                input.toolsIncludedInCurrent(),
                input.toolsIncludedInPrevious(),
                curr.size(),
                prev.size(),
                summary);
    }

    private CacheDiagnosticResult buildToolsChanged(CacheDiagnosticInput input,
                                                    List<CanonicalMessage> curr,
                                                    String currentHash,
                                                    String previousHash,
                                                    int previewLimit) {
        RawPayloadSignals rawSignals = computeRawPayloadSignals(input);
        List<CanonicalMessage> prev = input.previousMessages();
        int firstDiffIndex = firstMessageDiffIndex(prev, curr);
        String firstDiffRole = firstDiffRole(prev, curr, firstDiffIndex);
        int lcpLength;
        double lcpRatio;
        if (firstDiffIndex == -1) {
            lcpLength = Math.min(prev.size(), curr.size());
            lcpRatio = 1d;
        } else {
            String prevJoined = hasher.joinedForLcp(prev);
            String currJoined = hasher.joinedForLcp(curr);
            lcpLength = CanonicalMessagesHasher.charLcpLength(prevJoined, currJoined);
            int minLen = Math.min(prevJoined.length(), currJoined.length());
            lcpRatio = minLen == 0 ? 0d : (double) lcpLength / (double) minLen;
        }
        String currentPreview = previewAtDiff(curr, firstDiffIndex, previewLimit);
        String previousPreview = previewAtDiff(prev, firstDiffIndex, previewLimit);
        String summary = buildSummary(CacheDiagnosticCategory.TOOLS_CHANGED,
                prev.size(), curr.size(), firstDiffIndex, firstDiffRole, lcpRatio, lcpLength,
                input.toolsIncludedInPrevious(), input.toolsIncludedInCurrent());
        return new CacheDiagnosticResult(
                CacheDiagnosticCategory.TOOLS_CHANGED,
                currentHash,
                previousHash,
                lcpLength,
                lcpRatio,
                firstDiffIndex,
                firstDiffRole,
                previousPreview,
                currentPreview,
                rawSignals.currentHash,
                rawSignals.previousHash,
                rawSignals.lcpLength,
                input.toolsIncludedInCurrent(),
                input.toolsIncludedInPrevious(),
                curr.size(),
                prev.size(),
                summary);
    }

    private CacheDiagnosticCategory classify(List<CanonicalMessage> prev,
                                             List<CanonicalMessage> curr,
                                             int firstDiffIndex) {
        boolean prevLonger = prev.size() > curr.size();
        boolean prevIsPrefix = firstDiffIndex == prev.size() && prev.size() < curr.size();
        boolean currIsPrefix = firstDiffIndex == curr.size() && curr.size() < prev.size();

        if (prevIsPrefix) {
            return CacheDiagnosticCategory.APPEND_ONLY_OK;
        }

        if (prevLonger && compactionLikely(prev, curr)) {
            return CacheDiagnosticCategory.COMPACTION_RESET;
        }

        // current 是 previous 的前缀：消息被截断；按 compation 触发同等处理
        if (currIsPrefix) {
            return CacheDiagnosticCategory.COMPACTION_RESET;
        }

        if (firstDiffIndex >= 0 && firstDiffIndex <= 1) {
            return CacheDiagnosticCategory.EARLY_PREFIX_DRIFT;
        }

        if (firstDiffIndex > 1) {
            return CacheDiagnosticCategory.HISTORY_REWRITTEN;
        }

        // 防御兜底（应当不会被触发）
        return CacheDiagnosticCategory.UNKNOWN;
    }

    private static boolean compactionLikely(List<CanonicalMessage> prev, List<CanonicalMessage> curr) {
        if (prev.isEmpty()) {
            return false;
        }
        // current 不到 previous 一半的体量；并且 previous 体量大于 2，避免单条消息场景误判
        return prev.size() > 2 && curr.size() * 2 < prev.size();
    }

    private static int firstMessageDiffIndex(List<CanonicalMessage> prev, List<CanonicalMessage> curr) {
        int min = Math.min(prev.size(), curr.size());
        for (int i = 0; i < min; i++) {
            if (!prev.get(i).equals(curr.get(i))) {
                return i;
            }
        }
        if (prev.size() == curr.size()) {
            return -1;
        }
        return min; // 较小者是较大者的前缀
    }

    private static String firstDiffRole(List<CanonicalMessage> prev, List<CanonicalMessage> curr, int firstDiffIndex) {
        if (firstDiffIndex < 0 || firstDiffIndex == Integer.MAX_VALUE) {
            return null;
        }
        // 优先取 current 的 role；如果 current 在该下标越界则退回 previous
        if (firstDiffIndex < curr.size()) {
            return curr.get(firstDiffIndex).role();
        }
        if (firstDiffIndex < prev.size()) {
            return prev.get(firstDiffIndex).role();
        }
        return null;
    }

    private String previewAtDiff(List<CanonicalMessage> messages, int firstDiffIndex, int limit) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        int idx;
        if (firstDiffIndex < 0) {
            idx = 0;
        } else if (firstDiffIndex < messages.size()) {
            idx = firstDiffIndex;
        } else {
            // 之前/之后都越界，取最后一帧
            idx = messages.size() - 1;
        }
        CanonicalMessage m = messages.get(idx);
        return redactor.preview(m.role(), m.content(), limit);
    }

    private RawPayloadSignals computeRawPayloadSignals(CacheDiagnosticInput input) {
        String prevRaw = input.previousRawPayload();
        String currRaw = input.currentRawPayload();
        if (prevRaw == null || currRaw == null) {
            return new RawPayloadSignals(null, null, -1);
        }
        int limit = input.rawPayloadHashLimit();
        String boundedPrev = prevRaw.length() > limit ? prevRaw.substring(0, limit) : prevRaw;
        String boundedCurr = currRaw.length() > limit ? currRaw.substring(0, limit) : currRaw;
        int lcp = CanonicalMessagesHasher.charLcpLength(boundedPrev, boundedCurr);
        return new RawPayloadSignals(
                hasher.hashRawPayload(boundedPrev),
                hasher.hashRawPayload(boundedCurr),
                lcp);
    }

    private static String buildSummary(CacheDiagnosticCategory category,
                                       int prevSize,
                                       int currSize,
                                       int firstDiffIndex,
                                       String firstDiffRole,
                                       double lcpRatio,
                                       int lcpLength,
                                       boolean toolsPrev,
                                       boolean toolsCurr) {
        return switch (category) {
            case FIRST_REQUEST -> "FIRST_REQUEST: " + currSize + " message(s), no previous baseline";
            case IDENTICAL -> "IDENTICAL: " + currSize + " message(s), canonical hash unchanged";
            case APPEND_ONLY_OK -> "APPEND_ONLY_OK: " + prevSize + " → " + currSize
                    + " message(s), prefix preserved, lcp=" + percent(lcpRatio);
            case EARLY_PREFIX_DRIFT -> "EARLY_PREFIX_DRIFT: first diff at index " + firstDiffIndex
                    + (StringUtils.isBlank(firstDiffRole) ? "" : " (" + firstDiffRole + ")")
                    + ", lcp=" + percent(lcpRatio);
            case TOOLS_CHANGED -> "TOOLS_CHANGED: tools " + (toolsPrev ? "present" : "absent")
                    + " → " + (toolsCurr ? "present" : "absent")
                    + ", " + prevSize + " → " + currSize + " message(s), lcp=" + percent(lcpRatio);
            case HISTORY_REWRITTEN -> "HISTORY_REWRITTEN: first diff at index " + firstDiffIndex
                    + (StringUtils.isBlank(firstDiffRole) ? "" : " (" + firstDiffRole + ")")
                    + ", " + prevSize + " → " + currSize + " message(s), lcp=" + percent(lcpRatio);
            case COMPACTION_RESET -> "COMPACTION_RESET: " + prevSize + " → " + currSize
                    + " message(s), lcp=" + percent(lcpRatio);
            case UNKNOWN -> "UNKNOWN: " + prevSize + " → " + currSize
                    + " message(s), lcp=" + percent(lcpRatio) + ", lcpLength=" + lcpLength;
        };
    }

    private static String percent(double ratio) {
        if (Double.isNaN(ratio) || ratio < 0) {
            return "0%";
        }
        int pct = (int) Math.round(ratio * 100);
        if (pct > 100) pct = 100;
        return pct + "%";
    }

    private static final class RawPayloadSignals {
        final String previousHash;
        final String currentHash;
        final int lcpLength;

        RawPayloadSignals(String previousHash, String currentHash, int lcpLength) {
            this.previousHash = previousHash;
            this.currentHash = currentHash;
            this.lcpLength = lcpLength;
        }

        String summarySuffix() {
            if (previousHash == null && currentHash == null) {
                return "";
            }
            return ", rawPayloadLcp=" + lcpLength;
        }
    }
}
