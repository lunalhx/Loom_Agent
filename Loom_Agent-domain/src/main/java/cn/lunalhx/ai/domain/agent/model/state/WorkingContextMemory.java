package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory.MemoryNote;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
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
 *   <li>Episodic/process notes — max 12, structured as {@link MemoryNote}.</li>
 * </ul>
 *
 * <p>v8 compatibility: older snapshots persisted {@code notes} as a flat
 * {@code List<String>}. The {@link MemoryNoteDeserializer} normalizes legacy
 * string notes into structured {@link MemoryNote} entries on restore.
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
    @JsonDeserialize(contentUsing = MemoryNoteDeserializer.class)
    private List<MemoryNote> notes = new ArrayList<>();

    public WorkingContextMemory() {
    }

    public String taskSummary() { return taskSummary; }
    public List<String> recentFiles() { return List.copyOf(recentFiles); }
    public Map<String, FileSummary> fileSummaries() { return Map.copyOf(fileSummaries); }
    public List<MemoryNote> notes() { return List.copyOf(notes); }

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

    /** Append a structured episodic/process note, bounded to 12 (oldest dropped). */
    public void addNote(MemoryNote note) {
        if (note == null || note.text() == null || note.text().isBlank()) {
            return;
        }
        notes.add(note);
        while (notes.size() > MAX_NOTES) {
            notes.remove(0);
        }
    }

    /** Append a plain episodic note with no tags (defaults to {@code process} kind). */
    public void addNote(String note) {
        addNote(MemoryNote.of(note));
    }

    /** Convenience for callers that have a source/tag. */
    public void addNote(String text, List<String> tags, String source, String kind) {
        addNote(MemoryNote.builder()
                .text(text)
                .tags(tags == null ? List.of() : List.copyOf(tags))
                .source(source)
                .kind(kind)
                .createdAt(Instant.now())
                .build());
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

    /**
     * Immutable structured episodic/process note.
     *
     * <p>Fields: {@code text}, {@code tags}, {@code source}, {@code createdAt},
     * {@code sequence}, and {@code kind}.
     */
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class MemoryNote {
        private String text;
        private List<String> tags;
        private String source;
        private Instant createdAt;
        private long sequence;
        private String kind;

        /** No-arg constructor for Jackson deserialization. */
        public MemoryNote() {
            this.text = null;
            this.tags = List.of();
            this.source = null;
            this.createdAt = Instant.now();
            this.sequence = 0L;
            this.kind = "process";
        }

        public MemoryNote(String text, List<String> tags, String source,
                          Instant createdAt, long sequence, String kind) {
            this.text = text;
            this.tags = tags == null ? List.of() : List.copyOf(tags);
            this.source = source;
            this.createdAt = createdAt == null ? Instant.now() : createdAt;
            this.sequence = sequence;
            this.kind = kind == null ? "process" : kind;
        }

        public static MemoryNote of(String text) {
            return new MemoryNote(text, List.of(), null, Instant.now(), 0L, "process");
        }

        public static Builder builder() {
            return new Builder();
        }

        public String text() { return text; }
        public List<String> tags() { return tags; }
        public String source() { return source; }
        public Instant createdAt() { return createdAt; }
        public long sequence() { return sequence; }
        public String kind() { return kind; }

        public static final class Builder {
            private String text;
            private List<String> tags;
            private String source;
            private Instant createdAt = Instant.now();
            private long sequence;
            private String kind = "process";

            public Builder text(String v) { this.text = v; return this; }
            public Builder tags(List<String> v) { this.tags = v; return this; }
            public Builder source(String v) { this.source = v; return this; }
            public Builder createdAt(Instant v) { this.createdAt = v; return this; }
            public Builder sequence(long v) { this.sequence = v; return this; }
            public Builder kind(String v) { this.kind = v; return this; }

            public MemoryNote build() {
                return new MemoryNote(text, tags, source, createdAt, sequence, kind);
            }
        }
    }

    /**
     * Deserializes a single note element. A legacy string is normalized into a
     * {@link MemoryNote}; an object is deserialized as a {@link MemoryNote}.
     */
    public static final class MemoryNoteDeserializer extends JsonDeserializer<MemoryNote> {
        @Override
        public MemoryNote deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.getCurrentToken() == null) {
                p.nextToken();
            }
            if (p.currentToken().isScalarValue()) {
                String text = p.getText();
                return text == null ? null : MemoryNote.of(text);
            }
            return ctxt.readValue(p, MemoryNote.class);
        }
    }
}
