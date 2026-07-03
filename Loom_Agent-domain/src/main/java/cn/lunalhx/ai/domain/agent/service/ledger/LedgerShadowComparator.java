package cn.lunalhx.ai.domain.agent.service.ledger;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedgerEntry;
import cn.lunalhx.ai.domain.agent.model.entity.LedgerShadowResult;
import cn.lunalhx.ai.domain.agent.model.valobj.LedgerStableType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Compares the current (old) prompt text against the conversation ledger
 * and the previous canonical snapshot.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Build {@link CanonicalSnapshot} from ledger entries.</li>
 *   <li>Compute {@link ComparisonStatus} via structured message-level
 *       prefix comparison (never string concatenation).</li>
 *   <li>Semantic coverage: check whether each block in the old prompt
 *       has a corresponding entry in the ledger or StablePrefix.</li>
 *   <li>Optional blocks are {@code "n/a"} when absent from old prompt,
 *       never hardcoded {@code true}.</li>
 *   <li>Desensitize all stored/diagnostic text.</li>
 * </ul>
 */
public final class LedgerShadowComparator {

    // ---- semantic block labels ----
    private static final String BLOCK_PROTOCOL = "协议";
    private static final String BLOCK_SKILLS = "Skills";
    private static final String BLOCK_TOOLS = "Tools";
    private static final String BLOCK_USER_TASK = "用户任务";
    private static final String BLOCK_PLAN = "计划";
    private static final String BLOCK_BUDGET = "预算";
    private static final String BLOCK_ASSISTANT = "Assistant输出";
    private static final String BLOCK_TOOL_RESULT = "工具结果";
    private static final String BLOCK_ERROR = "错误";
    private static final String BLOCK_REMINDER = "提醒";
    private static final String BLOCK_USER_INPUT = "用户输入";
    private static final String BLOCK_CONTINUATION = "续接消息";

    public static final String COVERED = "covered";
    public static final String NOT_COVERED = "not_covered";
    public static final String NA = "n/a";

    private static final int MAX_DIAGNOSTIC_BODY_CHARS = 40_000;

    /**
     * Run the full comparison.
     *
     * @param context       agent context
     * @param oldPromptText the rendered prompt string
     * @param previous      previous canonical snapshot, or null
     * @param diagnosticEnabled whether to populate diagnostic body
     * @return comparison result
     */
    public LedgerShadowResult compare(AgentContext context,
                                       String oldPromptText,
                                       CanonicalSnapshot previous,
                                       boolean diagnosticEnabled) {
        // 1. Build current snapshot
        CanonicalSnapshot current = CanonicalSnapshot.from(context);
        int generation = current.generation();

        // 2. Determine comparison status
        ComparisonStatus status;
        int msgLcp = 0;
        int charLcp = 0;
        int firstDiffIdx = -1;
        int prevMsgCnt = previous == null ? 0 : previous.messageCount();
        int curMsgCnt = current.messageCount();

        // 2a. Check for invalid ledger (duplicate sequences)
        if (context.getConversationLedger() != null
                && hasDuplicateSequences(context.getConversationLedger().entries())) {
            status = ComparisonStatus.INVALID_LEDGER;
        }
        // 2b. First call (no previous snapshot at all)
        else if (previous == null) {
            status = ComparisonStatus.INITIAL;
        }
        // 2c. Generation changed — reset baseline
        else if (previous.generation() != generation) {
            status = ComparisonStatus.GENERATION_RESET;
            // Baseline is reset: previous count is effectively 0 in the new generation
            prevMsgCnt = 0;
        }
        // 2d. Message-level comparison
        else {
            msgLcp = countMatchingPrefixMessages(previous, current);
            if (msgLcp == prevMsgCnt && curMsgCnt == prevMsgCnt) {
                status = ComparisonStatus.IDENTICAL;
                firstDiffIdx = -1;
            } else if (msgLcp == prevMsgCnt && curMsgCnt > prevMsgCnt) {
                status = ComparisonStatus.APPEND_ONLY;
                firstDiffIdx = prevMsgCnt;
            } else {
                status = ComparisonStatus.REWRITTEN;
                firstDiffIdx = msgLcp;
            }
        }

        // 3. Char LCP (auxiliary)
        String prevText = previous != null ? serializeForLcp(previous) : null;
        String curText = serializeForLcp(current);
        charLcp = computeCharLcp(prevText, curText);

        // 4. Semantic coverage
        Map<String, String> coverage = buildCoverage(oldPromptText, context);
        List<String> mandatoryGaps = coverage.entrySet().stream()
                .filter(e -> NOT_COVERED.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // 5. Diagnostic body
        String diagnosticBody = null;
        if (diagnosticEnabled) {
            String body = buildDiagnosticText(current);
            body = desensitize(body);
            diagnosticBody = body.length() > MAX_DIAGNOSTIC_BODY_CHARS
                    ? body.substring(0, MAX_DIAGNOSTIC_BODY_CHARS)
                    + "\n... [truncated]"
                    : body;
        }

        return new LedgerShadowResult(
                coverage, mandatoryGaps, status,
                msgLcp, charLcp, prevMsgCnt, curMsgCnt,
                firstDiffIdx, generation,
                null, diagnosticEnabled, diagnosticBody);
    }

    // ================================================================
    // Message-level comparison
    // ================================================================

    /**
     * Count consecutive messages from the start that are identical
     * (same role AND same content) between previous and current snapshots.
     */
    public int countMatchingPrefixMessages(CanonicalSnapshot previous,
                                            CanonicalSnapshot current) {
        int max = Math.min(previous.messageCount(), current.messageCount());
        int match = 0;
        for (int i = 0; i < max; i++) {
            CanonicalSnapshot.Message pm = previous.messages().get(i);
            CanonicalSnapshot.Message cm = current.messages().get(i);
            if (pm.role().equals(cm.role()) && pm.content().equals(cm.content())) {
                match++;
            } else {
                break;
            }
        }
        return match;
    }

    /**
     * Check for duplicate sequence numbers in the entry list.
     */
    public boolean hasDuplicateSequences(List<ConversationLedgerEntry> entries) {
        if (entries.size() <= 1) return false;
        return entries.stream()
                .map(ConversationLedgerEntry::sequence)
                .distinct()
                .count() != entries.size();
    }

    // ================================================================
    // Canonical snapshot (testing access)
    // ================================================================

    public CanonicalSnapshot buildSnapshot(AgentContext context) {
        return CanonicalSnapshot.from(context);
    }

    // ================================================================
    // Semantic coverage
    // ================================================================

    /**
     * Build a coverage map.
     *
     * <p>Values: {@code "covered"}, {@code "not_covered"}, or {@code "n/a"}.
     * "n/a" means the block was not present in the old prompt and is
     * optional — it is not a gap.
     */
    public Map<String, String> buildCoverage(String oldPromptText, AgentContext context) {
        Map<String, String> coverage = new LinkedHashMap<>();
        boolean hasStablePrefix = context.getStablePrefix() != null;
        List<ConversationLedgerEntry> entries = context.getConversationLedger() != null
                ? context.getConversationLedger().entries() : List.of();

        // 协议 — always covered when StablePrefix exists
        coverage.put(BLOCK_PROTOCOL, hasStablePrefix ? COVERED : NOT_COVERED);

        // Skills — from StablePrefix frozenContent (C3)
        coverage.put(BLOCK_SKILLS, hasStablePrefix ? COVERED : NOT_COVERED);

        // Tools — from StablePrefix frozenContent (C3)
        coverage.put(BLOCK_TOOLS, hasStablePrefix ? COVERED : NOT_COVERED);

        // 用户任务 — mandatory, must be in ledger
        boolean hasUserTask = entries.stream()
                .anyMatch(e -> e.stableType() == LedgerStableType.USER_TASK);
        coverage.put(BLOCK_USER_TASK, hasUserTask ? COVERED : NOT_COVERED);

        // 计划 — check old prompt, then ledger
        boolean oldHasPlan = oldPromptText != null
                && oldPromptText.contains("当前计划：");
        if (oldHasPlan) {
            boolean hasPlan = entries.stream()
                    .anyMatch(e -> e.stableType() == LedgerStableType.CONTROL_UPDATE
                            && e.content().contains("[Plan v"));
            coverage.put(BLOCK_PLAN, hasPlan ? COVERED : NOT_COVERED);
        } else {
            coverage.put(BLOCK_PLAN, NA);
        }

        // 预算 — check old prompt, then ledger
        boolean oldHasBudget = oldPromptText != null && (
                oldPromptText.contains("执行预算：")
                        || oldPromptText.contains("步预算"));
        if (oldHasBudget) {
            boolean hasBudget = entries.stream()
                    .anyMatch(e -> e.stableType() == LedgerStableType.CONTROL_UPDATE
                            && e.content().contains("[Step Budget]"));
            coverage.put(BLOCK_BUDGET, hasBudget ? COVERED : NOT_COVERED);
        } else {
            coverage.put(BLOCK_BUDGET, NA);
        }

        // Assistant输出 — check old prompt, then ledger
        boolean oldHasAssistant = oldPromptText != null
                && oldPromptText.contains("动态上下文：");
        if (oldHasAssistant) {
            boolean hasAssistant = entries.stream()
                    .anyMatch(e -> e.stableType() == LedgerStableType.ASSISTANT_ACTION);
            coverage.put(BLOCK_ASSISTANT, hasAssistant ? COVERED : NOT_COVERED);
        } else {
            coverage.put(BLOCK_ASSISTANT, NA);
        }

        // 工具结果 — same as assistant (same old prompt section)
        if (oldHasAssistant) {
            boolean hasToolResult = entries.stream()
                    .anyMatch(e -> e.stableType() == LedgerStableType.TOOL_RESULT);
            coverage.put(BLOCK_TOOL_RESULT, hasToolResult ? COVERED : NOT_COVERED);
        } else {
            coverage.put(BLOCK_TOOL_RESULT, NA);
        }

        // 错误 — check old prompt, then ledger
        boolean oldHasError = oldPromptText != null
                && oldPromptText.contains("[Parse Error]");
        if (oldHasError) {
            boolean hasError = entries.stream()
                    .anyMatch(e -> e.stableType() == LedgerStableType.CONTROL_UPDATE
                            && e.content().contains("[Parse Error]"));
            coverage.put(BLOCK_ERROR, hasError ? COVERED : NOT_COVERED);
        } else {
            coverage.put(BLOCK_ERROR, NA);
        }

        // 提醒 — check old prompt, then ledger
        boolean oldHasReminder = oldPromptText != null
                && oldPromptText.contains("todo_write");
        if (oldHasReminder) {
            boolean hasReminder = entries.stream()
                    .anyMatch(e -> e.stableType() == LedgerStableType.CONTROL_UPDATE
                            && e.content().contains("todo_write"));
            coverage.put(BLOCK_REMINDER, hasReminder ? COVERED : NOT_COVERED);
        } else {
            coverage.put(BLOCK_REMINDER, NA);
        }

        // 用户输入 — check old prompt, then ledger
        boolean oldHasUserInput = oldPromptText != null
                && oldPromptText.contains("[User Input]");
        if (oldHasUserInput) {
            boolean hasUserInput = entries.stream()
                    .anyMatch(e -> e.stableType() == LedgerStableType.USER_INPUT);
            coverage.put(BLOCK_USER_INPUT, hasUserInput ? COVERED : NOT_COVERED);
        } else {
            coverage.put(BLOCK_USER_INPUT, NA);
        }

        // 续接消息 — check old prompt, then ledger
        boolean oldHasCont = oldPromptText != null
                && oldPromptText.contains("[Conversation Continued]");
        if (oldHasCont) {
            boolean hasCont = entries.stream()
                    .anyMatch(e -> e.stableType() == LedgerStableType.USER_INPUT
                            && e.content().contains("[Conversation Continued]"));
            coverage.put(BLOCK_CONTINUATION, hasCont ? COVERED : NOT_COVERED);
        } else {
            coverage.put(BLOCK_CONTINUATION, NA);
        }

        return coverage;
    }

    // ================================================================
    // Serialization (for LCP / diagnostic)
    // ================================================================

    /**
     * Serialize a snapshot to a deterministic string for char-level LCP
     * computation. This is auxiliary only — the primary comparison is
     * message-level.
     */
    String serializeForLcp(CanonicalSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("[system]\n").append(snapshot.system()).append('\n');
        for (CanonicalSnapshot.Message m : snapshot.messages()) {
            sb.append('[').append(m.role()).append("]\n");
            sb.append(m.content()).append('\n');
        }
        return sb.toString();
    }

    /** Build diagnostic text: message count/role only, NO raw content. */
    String buildDiagnosticText(CanonicalSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("generation=").append(snapshot.generation()).append('\n');
        sb.append("system.length=").append(snapshot.system().length()).append('\n');
        sb.append("system.sha256=").append(sha256Hex(snapshot.system())).append('\n');
        for (int i = 0; i < snapshot.messages().size(); i++) {
            CanonicalSnapshot.Message m = snapshot.messages().get(i);
            sb.append('[').append(i).append("] role=").append(m.role())
                    .append(" len=").append(m.content().length())
                    .append(" sha256=").append(sha256Hex(m.content()))
                    .append('\n');
        }
        return sb.toString();
    }

    /** Simple SHA-256 hex for diagnostic comparison (no raw content). */
    private static String sha256Hex(String input) {
        if (input == null || input.isEmpty()) return "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "error";
        }
    }

    // ================================================================
    // Char LCP (auxiliary)
    // ================================================================

    /**
     * Auxiliary character-level LCP between two serialized snapshots.
     */
    public int computeCharLcp(String previous, String current) {
        if (previous == null || current == null) return 0;
        int max = Math.min(previous.length(), current.length());
        int lcp = 0;
        while (lcp < max && previous.charAt(lcp) == current.charAt(lcp)) {
            lcp++;
        }
        return lcp;
    }

    // ================================================================
    // Desensitization
    // ================================================================

    /**
     * Strip secrets, tokens, keys, runIds, UUIDs, and paths from text.
     */
    public static String desensitize(String text) {
        if (text == null) return null;
        return text
                // Bearer tokens
                .replaceAll("Bearer\\s+[^\\s,;]+", "Bearer <redacted>")
                // OpenAI/Anthropic API keys (sk-...)
                .replaceAll("\\bsk-[a-zA-Z0-9_-]{20,}\\b", "<api-key>")
                // Generic key=value with sensitive names
                .replaceAll("(?i)(api[_-]?key|apikey|secret|password|token)\\s*[:=]\\s*[^\\s,;]+",
                        "$1=<redacted>")
                // Environment variable assignments
                .replaceAll("\\b[A-Z_]{3,}=[^\\s,;]+", "<env-var>")
                // UUIDs
                .replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                        "<uuid>")
                // Run-id patterns
                .replaceAll("\\br-[a-zA-Z0-9_-]{3,40}\\b", "<runId>")
                // /tmp paths
                .replaceAll("/tmp/[a-zA-Z0-9/_.-]+", "/tmp/<path>")
                // SHA-256 hashes that appear outside our own diagnostic
                .replaceAll("\\bsha256-[a-f0-9]{16,64}\\b", "<sha256>");
    }
}
