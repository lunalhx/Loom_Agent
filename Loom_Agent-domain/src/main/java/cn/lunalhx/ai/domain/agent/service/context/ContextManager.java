package cn.lunalhx.ai.domain.agent.service.context;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistory;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryEntry;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ConversationEntryType;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextProperties;
import cn.lunalhx.ai.domain.common.UntrustedContentSanitizer;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a temporary, section-budgeted send view of the model context each
 * round, mirroring loom-code's {@code ContextManager}.
 *
 * <p>Fixed section order: {@code prefix → memory → relevant_memory → history →
 * current_request}. The raw {@code ConversationHistory} is NEVER mutated;
 * every build produces an immutable {@link ContextBuildResult}.
 *
 * <p>Section budgets (chars) and floors default to loom-code values and are
 * configured via {@code ContextProperties}. When {@code contextReductionEnabled}
 * is false, sections are sent without trimming (current request still last).
 */
public final class ContextManager {

    private final AgentRuntimeProperties properties;

    public ContextManager(AgentRuntimeProperties properties) {
        this.properties = properties == null ? new AgentRuntimeProperties() : properties;
    }

    public ContextBuildResult build(AgentContext context) {
        ContextProperties cfg = contextProperties(context);
        boolean reductionEnabled = Boolean.TRUE.equals(cfg.getContextReductionEnabled());

        String prefix = renderPrefix(context);
        String memory = renderMemory(context);
        String relevantMemory = renderRelevantMemory(context);
        HistoryRender history = renderHistory(context);
        String currentRequest = renderCurrentRequest(context);

        if (!reductionEnabled) {
            return assembleRaw(prefix, memory, relevantMemory, history.text(), currentRequest, context);
        }

        return assembleBudgeted(prefix, memory, relevantMemory, history, currentRequest, context, cfg);
    }

    /** Budget estimation text used by {@code BudgetMiddleware}. */
    public String budgetInput(AgentContext context) {
        return build(context).budgetText();
    }

    /**
     * Build a floor-pressed view for the single overflow retry: all four
     * trimmed sections are forced to their floors and the current request is
     * preserved (never trimmed). Used when the provider still reports
     * context overflow under the normal budgeted view.
     */
    public ContextBuildResult buildFloorPressed(AgentContext context) {
        ContextProperties cfg = contextProperties(context);
        String prefix = renderPrefix(context);
        String memory = renderMemory(context);
        String relevantMemory = renderRelevantMemory(context);
        HistoryRender history = renderHistory(context);
        String currentRequest = renderCurrentRequest(context);

        String trimmedPrefix = toFloor(prefix, positive(cfg.getPrefixFloorChars(), 1200), "prefix");
        String trimmedMemory = toFloor(memory, positive(cfg.getMemoryFloorChars(), 400), "memory");
        String trimmedRelevant = toFloor(relevantMemory, positive(cfg.getRelevantMemoryFloorChars(), 300), "relevant_memory");
        String trimmedHistory = toFloor(history.text(), positive(cfg.getHistoryFloorChars(), 1500), "history");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.builder().role("user").content(trimmedMemory).build());
        messages.add(ChatMessage.builder().role("user").content(trimmedRelevant).build());
        messages.add(ChatMessage.builder().role("user").content(trimmedHistory).build());
        messages.add(ChatMessage.builder().role("user").content(currentRequest).build());

        ContextBuildResult.ContextRenderMetadata metadata =
                new ContextBuildResult.ContextRenderMetadata(
                        trimmedPrefix.length() + trimmedMemory.length() + trimmedRelevant.length()
                                + trimmedHistory.length() + currentRequest.length(),
                        positive(cfg.getTotalBudgetChars(), 12000),
                        Map.of("prefix", prefix.length(), "memory", memory.length(),
                                "relevant_memory", relevantMemory.length(),
                                "history", history.text().length(),
                                "current_request", currentRequest.length()),
                        Map.of("prefix", trimmedPrefix.length(), "memory", trimmedMemory.length(),
                                "relevant_memory", trimmedRelevant.length(),
                                "history", trimmedHistory.length(),
                                "current_request", currentRequest.length()),
                        List.of("floor_pressure"), true, history.merged(), history.summarized(), 0,
                        currentRequest, true, currentRequest.length());
        return new ContextBuildResult(trimmedPrefix, messages, metadata, false, null);
    }

    private String toFloor(String value, int floor, String section) {
        if (value == null || value.length() <= floor) {
            return value == null ? "" : value;
        }
        int head = (int) (floor * 0.75);
        int tail = floor - head;
        return value.substring(0, head) + "\n[...] " + value.substring(value.length() - tail);
    }

    // ================================================================
    // Section builders
    // ================================================================

    private String renderPrefix(AgentContext context) {
        StablePrefix stablePrefix = context.getStablePrefix();
        return stablePrefix == null ? "" : stablePrefix.frozenContent();
    }

    private String renderMemory(AgentContext context) {
        WorkingContextMemory wm = context.getWorkingMemory();
        if (wm == null || wm.isEmpty()) {
            return "Working memory: - none";
        }
        StringBuilder sb = new StringBuilder("Working memory:\n");
        if (wm.taskSummary() != null) {
            sb.append("- task: ").append(wm.taskSummary()).append('\n');
        }
        if (!wm.recentFiles().isEmpty()) {
            sb.append("- recent files: ").append(String.join(", ", wm.recentFiles())).append('\n');
        }
        for (WorkingContextMemory.FileSummary fs : wm.fileSummaries().values()) {
            sb.append("- file: ").append(fs.path()).append('\n')
                    .append("  summary: ").append(fs.summary()).append('\n');
        }
        if (!wm.notes().isEmpty()) {
            for (String note : wm.notes()) {
                sb.append("- note: ").append(note).append('\n');
            }
        }
        return sb.toString();
    }

    private String renderRelevantMemory(AgentContext context) {
        WorkingContextMemory wm = context.getWorkingMemory();
        if (wm == null || (wm.notes().isEmpty() && wm.fileSummaries().isEmpty())) {
            return "Relevant memory: - none";
        }
        ContextProperties cfg = contextProperties(context);
        int limit = positive(cfg.getRelevantMemoryLimit(), 3);
        StringBuilder sb = new StringBuilder("Relevant memory:\n");
        int added = 0;
        // Prioritize episodic notes first, then file summaries (newest first).
        List<String> notes = wm.notes();
        for (int i = notes.size() - 1; i >= 0 && added < limit; i--, added++) {
            sb.append("- ").append(notes.get(i)).append('\n');
        }
        for (WorkingContextMemory.FileSummary fs : wm.fileSummaries().values()) {
            if (added >= limit) {
                break;
            }
            sb.append("- ").append(fs.path()).append(": ").append(fs.summary()).append('\n');
            added++;
        }
        return sb.toString();
    }

    private HistoryRender renderHistory(AgentContext context) {
        ConversationHistory history = context.getConversationHistory();
        if (history == null || history.isEmpty()) {
            return new HistoryRender("", 0, 0);
        }
        ContextProperties cfg = contextProperties(context);
        int recentItems = positive(cfg.getRecentHistoryItems(), 6);

        List<ConversationHistoryEntry> entries = history.entries();
        // The current request (last USER_TASK/USER_INPUT) is sent separately as
        // the final user message — exclude only that entry from the history
        // projection to avoid duplication.
        int currentRequestIndex = currentRequestEntryIndex(entries);
        List<ConversationHistoryEntry> historyEntries;
        if (currentRequestIndex < 0) {
            historyEntries = new ArrayList<>(entries);
        } else if (currentRequestIndex == entries.size() - 1) {
            historyEntries = new ArrayList<>(entries.subList(0, entries.size() - 1));
        } else {
            historyEntries = new ArrayList<>();
            historyEntries.addAll(entries.subList(0, currentRequestIndex));
            historyEntries.addAll(entries.subList(currentRequestIndex + 1, entries.size()));
        }

        List<LogicalItem> logical = toLogicalItems(historyEntries);

        // Select the last `recentItems` logical items as recent window.
        List<LogicalItem> recent = logical.size() > recentItems
                ? new ArrayList<>(logical.subList(logical.size() - recentItems, logical.size()))
                : new ArrayList<>(logical);

        int merged = logical.size() - recent.size();
        StringBuilder sb = new StringBuilder("History:\n");
        int summarized = 0;
        for (LogicalItem item : recent) {
            sb.append(renderLogicalItem(item));
            if (item.summarized()) {
                summarized++;
            }
            sb.append('\n');
        }
        return new HistoryRender(sb.toString(), merged, summarized);
    }

    private int currentRequestEntryIndex(List<ConversationHistoryEntry> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            ConversationHistoryEntry entry = entries.get(i);
            if (entry.stableType() == ConversationEntryType.USER_TASK
                    || entry.stableType() == ConversationEntryType.USER_INPUT) {
                return i;
            }
        }
        return -1;
    }

    /** Merge assistant tool-call + tool-result pairs into one logical item. */
    private List<LogicalItem> toLogicalItems(List<ConversationHistoryEntry> entries) {
        List<LogicalItem> items = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            ConversationHistoryEntry entry = entries.get(i);
            if (entry.stableType() == ConversationEntryType.ASSISTANT_ACTION
                    && i + 1 < entries.size()
                    && entries.get(i + 1).stableType() == ConversationEntryType.TOOL_RESULT
                    && sameCorrelation(entry, entries.get(i + 1))) {
                items.add(LogicalItem.toolPair(entry, entries.get(i + 1)));
                i++;
            } else if (entry.stableType() == ConversationEntryType.TOOL_RESULT) {
                items.add(LogicalItem.single(entry));
            } else {
                items.add(LogicalItem.single(entry));
            }
        }
        return items;
    }

    private boolean sameCorrelation(ConversationHistoryEntry a, ConversationHistoryEntry b) {
        String ca = correlation(a.eventKey());
        String cb = correlation(b.eventKey());
        return !ca.isBlank() && ca.equals(cb);
    }

    private String correlation(String eventKey) {
        if (eventKey == null) {
            return "";
        }
        int lastSeparator = eventKey.lastIndexOf(':');
        return lastSeparator < 0 ? "" : eventKey.substring(0, lastSeparator);
    }

    private String renderLogicalItem(LogicalItem item) {
        if (item.type() == LogicalItem.Type.TOOL_PAIR) {
            ConversationHistoryEntry assistant = item.first();
            ConversationHistoryEntry result = item.second();
            String toolName = assistant.toolName() != null ? assistant.toolName()
                    : (result.toolName() != null ? result.toolName() : "tool");
            String content = unwrapToolBoundary(result.content());
            return "[tool:" + toolName + "] args + result:\n"
                    + "Result: " + firstLines(UntrustedContentSanitizer.escapeXml(content), 3);
        }
        if (item.type() == LogicalItem.Type.TOOL_RESULT) {
            String toolName = item.first().toolName() != null ? item.first().toolName() : "tool";
            return "[tool:" + toolName + "] result:\n"
                    + "Result: " + firstLines(UntrustedContentSanitizer.escapeXml(unwrapToolBoundary(item.first().content())), 3);
        }
        // user/assistant message or other
        String role = item.first().role();
        if ("assistant".equals(role)) {
            return "Assistant: " + singleLine(item.first().content());
        }
        return "User: " + singleLine(item.first().content());
    }

    private String unwrapToolBoundary(String content) {
        String open = "<untrusted_tool_output>\n";
        String close = "\n</untrusted_tool_output>";
        if (content != null && content.startsWith(open) && content.endsWith(close)) {
            return content.substring(open.length(), content.length() - close.length());
        }
        return content == null ? "" : content;
    }

    private String firstLines(String value, int maxLines) {
        if (value == null) {
            return "";
        }
        String[] lines = value.split("\n", -1);
        int count = Math.min(lines.length, maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(abbreviate(lines[i], 300));
        }
        if (lines.length > count) {
            sb.append("\n[... ").append(lines.length - count).append(" more lines ...]");
        }
        return sb.toString();
    }

    private String singleLine(String value) {
        return value == null ? "" : abbreviate(value.replace('\n', ' '), 900);
    }

    private String renderCurrentRequest(AgentContext context) {
        ConversationHistory history = context.getConversationHistory();
        if (history == null || history.isEmpty()) {
            return context.getQuestion() == null ? "" : context.getQuestion();
        }
        List<ConversationHistoryEntry> entries = history.entries();
        for (int i = entries.size() - 1; i >= 0; i--) {
            ConversationHistoryEntry entry = entries.get(i);
            if (entry.stableType() == ConversationEntryType.USER_TASK
                    || entry.stableType() == ConversationEntryType.USER_INPUT) {
                return entry.content();
            }
        }
        return context.getQuestion() == null ? "" : context.getQuestion();
    }

    // ================================================================
    // Assembly
    // ================================================================

    private ContextBuildResult assembleRaw(String prefix, String memory, String relevantMemory,
                                           String historyText, String currentRequest,
                                           AgentContext context) {
        List<ChatMessage> messages = new ArrayList<>();
        if (memory.length() > 0) {
            messages.add(ChatMessage.builder().role("user")
                    .content(memory).build());
        }
        if (relevantMemory.length() > 0) {
            messages.add(ChatMessage.builder().role("user")
                    .content(relevantMemory).build());
        }
        if (historyText.length() > 0) {
            messages.add(ChatMessage.builder().role("user")
                    .content(historyText).build());
        }
        messages.add(ChatMessage.builder().role("user")
                .content(currentRequest).build());

        ContextBuildResult.ContextRenderMetadata metadata =
                new ContextBuildResult.ContextRenderMetadata(
                        prefix.length() + memory.length() + relevantMemory.length()
                                + historyText.length() + currentRequest.length(),
                        0,
                        Map.of("prefix", prefix.length(), "memory", memory.length(),
                                "relevant_memory", relevantMemory.length(),
                                "history", historyText.length(),
                                "current_request", currentRequest.length()),
                        Map.of(),
                        List.of(), false, 0, 0, 0, currentRequest, true, currentRequest.length());
        return new ContextBuildResult(prefix, messages, metadata, false, null);
    }

    private ContextBuildResult assembleBudgeted(String prefix, String memory, String relevantMemory,
                                                HistoryRender history, String currentRequest,
                                                AgentContext context, ContextProperties cfg) {
        int totalBudget = positive(cfg.getTotalBudgetChars(), 12000);
        int prefixBudget = positive(cfg.getPrefixBudgetChars(), 3600);
        int prefixFloor = positive(cfg.getPrefixFloorChars(), 1200);
        int memoryBudget = positive(cfg.getMemoryBudgetChars(), 1600);
        int memoryFloor = positive(cfg.getMemoryFloorChars(), 400);
        int relevantBudget = positive(cfg.getRelevantMemoryBudgetChars(), 1200);
        int relevantFloor = positive(cfg.getRelevantMemoryFloorChars(), 300);
        int historyBudget = positive(cfg.getHistoryBudgetChars(), 5200);
        int historyFloor = positive(cfg.getHistoryFloorChars(), 1500);

        // Current request always preserved and never trimmed.
        int currentRequestChars = currentRequest.length();

        String trimmedPrefix = trim(prefix, prefixBudget, prefixFloor, "prefix");
        String trimmedMemory = trim(memory, memoryBudget, memoryFloor, "memory");
        String trimmedRelevant = trim(relevantMemory, relevantBudget, relevantFloor, "relevant_memory");
        String trimmedHistory = trim(history.text(), historyBudget, historyFloor, "history");

        int total = trimmedPrefix.length() + trimmedMemory.length() + trimmedRelevant.length()
                + trimmedHistory.length() + currentRequestChars;

        List<String> reductions = new ArrayList<>();
        // If still over budget, reduce in fixed order: relevant_memory → history → memory → prefix.
        // Each section is pressed directly to its floor to avoid O(n²) single-char trims.
        if (total > totalBudget && trimmedRelevant.length() > relevantFloor) {
            trimmedRelevant = trim(trimmedRelevant, relevantFloor, relevantFloor, "relevant_memory");
            reductions.add("relevant_memory");
            total = recompute(trimmedPrefix, trimmedMemory, trimmedRelevant, trimmedHistory, currentRequestChars);
        }
        if (total > totalBudget && trimmedHistory.length() > historyFloor) {
            trimmedHistory = trim(trimmedHistory, historyFloor, historyFloor, "history");
            reductions.add("history");
            total = recompute(trimmedPrefix, trimmedMemory, trimmedRelevant, trimmedHistory, currentRequestChars);
        }
        if (total > totalBudget && trimmedMemory.length() > memoryFloor) {
            trimmedMemory = trim(trimmedMemory, memoryFloor, memoryFloor, "memory");
            reductions.add("memory");
            total = recompute(trimmedPrefix, trimmedMemory, trimmedRelevant, trimmedHistory, currentRequestChars);
        }
        if (total > totalBudget && trimmedPrefix.length() > prefixFloor) {
            trimmedPrefix = trim(trimmedPrefix, prefixFloor, prefixFloor, "prefix");
            reductions.add("prefix");
            total = recompute(trimmedPrefix, trimmedMemory, trimmedRelevant, trimmedHistory, currentRequestChars);
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.builder().role("user").content(trimmedMemory).build());
        messages.add(ChatMessage.builder().role("user").content(trimmedRelevant).build());
        messages.add(ChatMessage.builder().role("user").content(trimmedHistory).build());
        messages.add(ChatMessage.builder().role("user").content(currentRequest).build());

        ContextBuildResult.ContextRenderMetadata metadata =
                new ContextBuildResult.ContextRenderMetadata(
                        total, totalBudget,
                        Map.of("prefix", prefix.length(), "memory", memory.length(),
                                "relevant_memory", relevantMemory.length(),
                                "history", history.text().length(),
                                "current_request", currentRequestChars),
                        Map.of("prefix", trimmedPrefix.length(), "memory", trimmedMemory.length(),
                                "relevant_memory", trimmedRelevant.length(),
                                "history", trimmedHistory.length(),
                                "current_request", currentRequestChars),
                        reductions, true, history.merged(), history.summarized(), 0,
                        currentRequest, true, currentRequestChars);
        return new ContextBuildResult(trimmedPrefix, messages, metadata, false, null);
    }

    private String trim(String value, int budget, int floor, String section) {
        if (value == null || value.length() <= budget) {
            return value == null ? "" : value;
        }
        // Keep the head; never cut below the floor.
        int target = Math.max(floor, budget);
        if (value.length() <= target) {
            return value;
        }
        // Preserve a meaningful tail for history/prefix continuity.
        int head = (int) (target * 0.75);
        int tail = target - head;
        return value.substring(0, head) + "\n[...] " + value.substring(value.length() - tail);
    }

    private int recompute(String prefix, String memory, String relevant, String history, int currentRequestChars) {
        return prefix.length() + memory.length() + relevant.length() + history.length() + currentRequestChars;
    }

    private ContextProperties contextProperties(AgentContext context) {
        AgentRuntimeProperties effective = context.runtimeProperties(properties);
        if (effective == null || effective.getContext() == null) {
            return new ContextProperties();
        }
        return effective.getContext();
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /** Internal render state for the history section. */
    record HistoryRender(String text, int merged, int summarized) {
    }

    /** A logical history item, either a message, a tool result, or a merged pair. */
    record LogicalItem(Type type, ConversationHistoryEntry first, ConversationHistoryEntry second) {

        enum Type { MESSAGE, TOOL_RESULT, TOOL_PAIR }

        static LogicalItem single(ConversationHistoryEntry entry) {
            Type t = entry.stableType() == ConversationEntryType.TOOL_RESULT ? Type.TOOL_RESULT : Type.MESSAGE;
            return new LogicalItem(t, entry, null);
        }

        static LogicalItem toolPair(ConversationHistoryEntry assistant, ConversationHistoryEntry result) {
            return new LogicalItem(Type.TOOL_PAIR, assistant, result);
        }

        boolean summarized() {
            return type == Type.TOOL_PAIR;
        }
    }
}
