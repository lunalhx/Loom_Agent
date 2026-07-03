package cn.lunalhx.ai.domain.agent.service.ledger;

import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedgerEntry;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Renders {@link ConversationLedgerEntry} lists as human-readable text
 * for transcript artifact persistence and summary generation.
 */
final class LedgerTranscriptRenderer {

    /** Max chars per entry content in the transcript. */
    private static final int MAX_CONTENT_CHARS = 2000;

    /**
     * Render all entries as a compact text transcript.
     */
    String render(List<ConversationLedgerEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Ledger Transcript ===\n");
        sb.append("entries: ").append(entries.size()).append('\n');
        if (!entries.isEmpty()) {
            sb.append("firstSeq: ").append(entries.get(0).sequence()).append('\n');
            sb.append("lastSeq: ").append(entries.get(entries.size() - 1).sequence()).append('\n');
        }
        sb.append('\n');

        for (ConversationLedgerEntry entry : entries) {
            sb.append('[').append(entry.sequence()).append("] ")
                    .append(entry.role()).append(" (").append(entry.stableType().code()).append(')');
            if (entry.eventKey() != null) {
                sb.append(" key=").append(entry.eventKey());
            }
            sb.append(":\n");
            sb.append(StringUtils.abbreviate(entry.content(), MAX_CONTENT_CHARS)).append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Render recent entries as a compact tool/action trace for summary inclusion.
     */
    String renderRecentSummary(List<ConversationLedgerEntry> entries, int maxEntries) {
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, entries.size() - maxEntries);
        for (int i = start; i < entries.size(); i++) {
            ConversationLedgerEntry entry = entries.get(i);
            sb.append("  [").append(entry.sequence()).append("] ")
                    .append(entry.role()).append(" (").append(entry.stableType().code()).append(")");
            if (entry.eventKey() != null) {
                sb.append(" key=").append(entry.eventKey());
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
