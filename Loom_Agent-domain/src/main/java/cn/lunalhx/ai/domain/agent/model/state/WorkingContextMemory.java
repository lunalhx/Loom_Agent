package cn.lunalhx.ai.domain.agent.model.state;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight working memory persisted on {@code AgentPromptState}.
 *
 * <p>Mirrors loom-code's "working memory" concept: a small, curated set of
 * facts (current task summary, recent files, file summaries, episodic/process
 * notes) rendered into the model prompt each round. It is derived from tool
 * activity and does NOT replace the raw {@code ConversationHistory}.
 *
 * <p>Bound constraints:
 * <ul>
 *   <li>Task summary — max 300 chars.</li>
 *   <li>Recent files — deduplicated, max 8.</li>
 *   <li>File summaries — max 6 rendered, each with path/summary/createdAt/SHA-256.</li>
 *   <li>Episodic/process notes — max 12.</li>
 * </ul>
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class WorkingContextMemory {

    private static final int MAX_TASK_SUMMARY_CHARS = 300;
    private static final int MAX_RECENT_FILES = 8;
    private static final int MAX_FILE_SUMMARIES = 6;
    private static final int MAX_NOTES = 12;

    private String taskSummary;
    private List<String> recentFiles = new ArrayList<>();
    private Map<String, FileSummary> fileSummaries = new LinkedHashMap<>();
    private List<String> notes = new ArrayList<>();

    public WorkingContextMemory() {
    }

    public String taskSummary() { return taskSummary; }
    public List<String> recentFiles() { return List.copyOf(recentFiles); }
    public Map<String, FileSummary> fileSummaries() { return Map.copyOf(fileSummaries); }
    public List<String> notes() { return List.copyOf(notes); }

    public void setTaskSummary(String v) {
        this.taskSummary = v == null ? null : abbreviate(v, MAX_TASK_SUMMARY_CHARS);
    }

    /** Record a touched/recent file path (deduplicated, bounded to 8). */
    public void recordRecentFile(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        recentFiles.remove(path);
        recentFiles.add(0, path);
        while (recentFiles.size() > MAX_RECENT_FILES) {
            recentFiles.remove(recentFiles.size() - 1);
        }
    }

    /** Store a file summary, dropping the oldest when over the bound. */
    public void putFileSummary(FileSummary summary) {
        if (summary == null || summary.path() == null || summary.path().isBlank()) {
            return;
        }
        fileSummaries.put(summary.path(), summary);
        while (fileSummaries.size() > MAX_FILE_SUMMARIES) {
            String oldest = fileSummaries.keySet().iterator().next();
            fileSummaries.remove(oldest);
        }
    }

    /** Invalidate a file summary (e.g. after a write/edit changed the file). */
    public void invalidateFileSummary(String path) {
        if (path != null) {
            fileSummaries.remove(path);
        }
    }

    /** Append an episodic/process note, bounded to 12 (oldest dropped). */
    public void addNote(String note) {
        if (note == null || note.isBlank()) {
            return;
        }
        notes.add(note);
        while (notes.size() > MAX_NOTES) {
            notes.remove(0);
        }
    }

    /** True when no working memory has been captured yet. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() {
        return taskSummary == null
                && recentFiles.isEmpty()
                && fileSummaries.isEmpty()
                && notes.isEmpty();
    }

    public void clear() {
        this.taskSummary = null;
        this.recentFiles = new ArrayList<>();
        this.fileSummaries = new LinkedHashMap<>();
        this.notes = new ArrayList<>();
    }

    private static String abbreviate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /** Immutable summary for a single file with a freshness SHA-256. */
    public record FileSummary(String path, String summary, Instant createdAt, String sha256) {
    }
}
