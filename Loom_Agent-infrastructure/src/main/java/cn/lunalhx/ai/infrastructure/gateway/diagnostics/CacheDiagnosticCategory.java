package cn.lunalhx.ai.infrastructure.gateway.diagnostics;

/**
 * 缓存诊断分类。
 * <ul>
 *   <li>FIRST_REQUEST：没有可比较的 previous 请求；</li>
 *   <li>IDENTICAL：canonical 完全一致；</li>
 *   <li>APPEND_ONLY_OK：previous 是 current 的严格前缀；</li>
 *   <li>EARLY_PREFIX_DRIFT：首个差异出现在 system 或前两条消息；</li>
 *   <li>TOOLS_CHANGED：tools 字段在两次请求间发生变化；</li>
 *   <li>HISTORY_REWRITTEN：差异出现在中间历史区；</li>
 *   <li>COMPACTION_RESET：current 远短于 previous，疑似 compact/summary 触发的整体重置；</li>
 *   <li>UNKNOWN：无法归入上述分类时的兜底。</li>
 * </ul>
 */
public enum CacheDiagnosticCategory {
    FIRST_REQUEST,
    IDENTICAL,
    APPEND_ONLY_OK,
    EARLY_PREFIX_DRIFT,
    TOOLS_CHANGED,
    HISTORY_REWRITTEN,
    COMPACTION_RESET,
    UNKNOWN
}
