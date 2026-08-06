package cn.lunalhx.ai.domain.agent.service.context;

import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistory;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryEntry;
import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory;
import cn.lunalhx.ai.domain.agent.model.valobj.ConversationEntryType;
import cn.lunalhx.ai.domain.common.UntrustedContentSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the {@code history} section from the append-only
 * {@link ConversationHistory}.
 *
 * <p>Rules (from newest to oldest):
 * <ul>
 *   <li>The most recent {@code recentItems} logical items are always kept.</li>
 *   <li>Older duplicate {@code read_file} calls are folded on the normalized
 *       path and reuse a fresh file summary when available.</li>
 *   <li>Older {@code run_shell} calls become {@code command -> first 3 non-empty
 *       lines}.</li>
 *   <li>Other old tool results become a single line; old user/assistant
 *       messages become short lines.</li>
 *   <li>Items are packed into the history budget newest-first, with local
 *       clipping when a recent item still overflows.</li>
 * </ul>
 *
 * <p>Tool output and file summaries remain untrusted data and are escaped, so
 * they can never break out of the {@code <untrusted_tool_output>} boundary.
 */
final class HistoryRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record HistoryResult(String text, int merged, int summarized, int deduped, int summaryReuse) {
    }

    HistoryResult render(ConversationHistory history, int currentRequestIndex,
                         int recentItems, int budgetChars,
                         WorkingContextMemory wm) {
        return render(history, currentRequestIndex, recentItems, budgetChars, wm, true);
    }

    HistoryResult render(ConversationHistory history, int currentRequestIndex,
                         int recentItems, int budgetChars,
                         WorkingContextMemory wm, boolean compressed) {
        if (history == null || history.isEmpty()) {
            return new HistoryResult("", 0, 0, 0, 0);
        }
        List<ConversationHistoryEntry> entries = history.entries();
        List<ConversationHistoryEntry> projected = new ArrayList<>();
        if (currentRequestIndex >= 0) {
            for (int i = 0; i < entries.size(); i++) {
                if (i != currentRequestIndex) {
                    projected.add(entries.get(i));
                }
            }
        } else {
            projected.addAll(entries);
        }

        List<LogicalItem> logical = toLogicalItems(projected);
        int merged = logical.size();
        List<LogicalItem> recent = logical.size() > recentItems
                ? new ArrayList<>(logical.subList(logical.size() - recentItems, logical.size()))
                : new ArrayList<>(logical);
        List<LogicalItem> older = logical.size() > recentItems
                ? new ArrayList<>(logical.subList(0, logical.size() - recentItems))
                : new ArrayList<>();

        List<String> lines = new ArrayList<>();
        int summarized = 0;
        int deduped = 0;
        int summaryReuse = 0;

        Map<String, String> freshSummaries = new HashMap<>();
        if (wm != null) {
            for (WorkingContextMemory.FileSummary fs : wm.fileSummaries().values()) {
                if (summaryStillValid(fs)) {
                    freshSummaries.put(fs.path(), fs.summary());
                }
            }
        }

        // Render recent items first (newest wins budget), then older folded
        // items only if budget remains. Lines are packed newest-first and
        // reversed at the end so the transcript still reads chronologically.
        List<LogicalItem> ordered = new ArrayList<>(recent);
        ordered.addAll(older);

        List<String> packed = new ArrayList<>();
        StringBuilder sb = new StringBuilder("Transcript:\n");
        int used = TextUtil.length(sb.toString());
        for (int i = ordered.size() - 1; i >= 0; i--) {
            LogicalItem item = ordered.get(i);
            RenderOutcome outcome = renderLogicalItem(item, older.contains(item), freshSummaries, compressed);
            String line = outcome.line;
            if (line == null || line.isEmpty()) {
                continue;
            }
            if (outcome.deduped) {
                deduped++;
            }
            if (outcome.summaryReuse) {
                summaryReuse++;
            }
            if (outcome.summarized) {
                summarized++;
            }
            int lineChars = TextUtil.length(line) + 1;
            if (used + lineChars > budgetChars) {
                int remaining = budgetChars - used;
                if (remaining > 0) {
                    String clipped = TextUtil.singleLine(line, remaining);
                    packed.add(0, clipped);
                    used += TextUtil.length(clipped) + 1;
                }
                break;
            }
            packed.add(0, line);
            used += lineChars;
        }
        for (String line : packed) {
            sb.append(line).append('\n');
        }

        String text = sb.toString();
        // Final safety: bound the whole section to budget.
        if (TextUtil.length(text) > budgetChars) {
            text = TextUtil.clipHeadTail(text, budgetChars);
        }
        return new HistoryResult(text, merged, summarized, deduped, summaryReuse);
    }

    private RenderOutcome renderLogicalItem(LogicalItem item, boolean isOlder, Map<String, String> freshSummaries,
                                            boolean compressed) {
        if (item.type() == LogicalItem.Type.TOOL_PAIR) {
            ConversationHistoryEntry assistant = item.first();
            ConversationHistoryEntry result = item.second();
            String toolName = assistant.toolName() != null ? assistant.toolName()
                    : (result.toolName() != null ? result.toolName() : "tool");
            String content = unwrapToolBoundary(result.content());

            if ("read_file".equals(toolName)) {
                String path = readPath(assistant, result);
                if (isOlder) {
                    String fresh = path != null ? freshSummaries.get(path) : null;
                    if (fresh != null) {
                        return RenderOutcome.line(
                                "[tool:read_file] " + path + "\nResult: " + fresh, true, true, true);
                    }
                }
                return RenderOutcome.line(
                        "[tool:read_file]" + (path != null ? " " + path : "") + "\nResult: "
                                + firstLines(escape(content), 3), isOlder, true, false);
            }
            if ("run_shell".equals(toolName)) {
                return RenderOutcome.line(
                        "[tool:run_shell] command -> " + command(assistant) + "\n"
                                + firstLines(escape(content), 3), false, true, false);
            }
            if (isOlder) {
                return RenderOutcome.line(
                        "[tool:" + toolName + "] " + singleLine(escape(content)), false, false, false);
            }
            return RenderOutcome.line(
                    "[tool:" + toolName + "] result:\n" + firstLines(escape(content), 3),
                    false, true, false);
        }
        if (item.type() == LogicalItem.Type.TOOL_RESULT) {
            String toolName = item.first().toolName() != null ? item.first().toolName() : "tool";
            return RenderOutcome.line(
                    "[tool:" + toolName + "] " + singleLine(escape(unwrapToolBoundary(item.first().content()))),
                    false, false, false);
        }
        String role = item.first().role();
        if ("assistant".equals(role)) {
            return RenderOutcome.line("Assistant: " + (compressed
                    ? singleLine(item.first().content())
                    : item.first().content()), false, false, false);
        }
        return RenderOutcome.line("User: " + (compressed
                ? singleLine(item.first().content())
                : item.first().content()), false, false, false);
    }

    private String readPath(ConversationHistoryEntry assistant, ConversationHistoryEntry result) {
        String p = pathFromInput(assistant.toolInputJson());
        if (p != null) {
            return p;
        }
        return pathFromInput(result.toolInputJson());
    }

    private String pathFromInput(String toolInputJson) {
        if (toolInputJson == null || toolInputJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(toolInputJson);
            if (node == null) {
                return null;
            }
            if (node.path("path").isTextual()) {
                return node.path("path").asText();
            }
            if (node.path("filePath").isTextual()) {
                return node.path("filePath").asText();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    /** Re-verify the file's SHA-256 before trusting a cached summary. */
    private boolean summaryStillValid(WorkingContextMemory.FileSummary fs) {
        if (fs.sha256() == null || fs.path() == null) {
            return false;
        }
        try {
            return fs.sha256().equals(WorkingContextMemoryService.sha256OfFile(fs.path()));
        } catch (Exception e) {
            return false;
        }
    }

    private String command(ConversationHistoryEntry assistant) {        String toolInputJson = assistant.toolInputJson();
        if (toolInputJson != null && !toolInputJson.isBlank()) {
            try {
                JsonNode node = MAPPER.readTree(toolInputJson);
                if (node != null && node.path("command").isTextual()) {
                    String cmd = node.path("command").asText();
                    if (!cmd.isBlank()) {
                        return singleLine(cmd);
                    }
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return "(command)";
    }

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

    private String unwrapToolBoundary(String content) {
        return content == null ? "" : content;
    }

    private String escape(String value) {
        return UntrustedContentSanitizer.escapeXml(value == null ? "" : value);
    }

    private String firstLines(String value, int maxLines) {
        List<String> lines = TextUtil.nonEmptyLines(value, maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(TextUtil.head(lines.get(i), 300));
        }
        return sb.toString();
    }

    private String singleLine(String value) {
        return TextUtil.singleLine(value, 400);
    }

    private record RenderOutcome(String line, boolean deduped, boolean summarized, boolean summaryReuse) {
        static RenderOutcome line(String line, boolean deduped, boolean summarized, boolean summaryReuse) {
            return new RenderOutcome(line, deduped, summarized, summaryReuse);
        }
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
    }
}
