package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.ConversationEntryType;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Append-only conversation history.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Entries are only added; never removed or reordered.</li>
 *   <li>{@link #entries()} returns an immutable snapshot view.</li>
 *   <li>Each entry's {@code sequence} is a monotonically increasing counter
 *       assigned by the history.</li>
 *   <li>Entries with a non-null {@code eventKey} are deduplicated:
 *       the same event key will not produce a second entry.</li>
 * </ul>
 *
 * <p>It records conversation
 * structure for idempotency checks, diagnostics, and replay. It is the
 * type-safe, resumable implementation of loom-code {@code session.history}:
 * it only appends raw facts and never performs compaction itself.
 */
public final class ConversationHistory {

    private final List<ConversationHistoryEntry> entries = new ArrayList<>();
    private final Set<String> seenEventKeys = new HashSet<>();
    private long nextSequence;

    /**
     * Append an entry with an event key for idempotency.
     *
     * <p>If {@code eventKey} is non-null and has already been seen by this
     * history, the call is silently ignored — the entry is not duplicated.
     * Null event keys bypass deduplication (backward compatibility).
     *
     * @return {@code this} for fluent chaining
     * @throws NullPointerException     if role, content, or stableType is null
     * @throws IllegalArgumentException if role is blank or content is empty
     */
    public ConversationHistory appendWithEventKey(String role, String content,
                                                  ConversationEntryType stableType, String eventKey) {
        return appendWithEventKey(role, content, stableType, eventKey,
                null, null, null, null, null);
    }

    public ConversationHistory appendWithEventKey(String role, String content,
                                                  ConversationEntryType stableType, String eventKey,
                                                  String toolName, String artifactId,
                                                  Integer originalChars, Integer renderChars) {
        return appendWithEventKey(role, content, stableType, eventKey,
                toolName, null, artifactId, originalChars, renderChars);
    }

    public ConversationHistory appendWithEventKey(String role, String content,
                                                  ConversationEntryType stableType, String eventKey,
                                                  String toolName, String toolInputJson, String artifactId,
                                                  Integer originalChars, Integer renderChars) {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(stableType, "stableType must not be null");
        if (StringUtils.isBlank(role)) {
            throw new IllegalArgumentException("role must not be blank");
        }
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }
        if (eventKey != null && !seenEventKeys.add(eventKey)) {
            return this;
        }
        ConversationHistoryEntry entry = ConversationHistoryEntry.builder()
                .sequence(nextSequence++)
                .role(role)
                .content(content)
                .stableType(stableType)
                .eventKey(eventKey)
                .toolName(toolName)
                .toolInputJson(toolInputJson)
                .artifactId(artifactId)
                .originalChars(originalChars)
                .renderChars(renderChars)
                .build();
        entries.add(entry);
        return this;
    }

    /**
     * Append an entry without deduplication (backward compatible).
     *
     * @return {@code this} for fluent chaining
     */
    public ConversationHistory append(String role, String content, ConversationEntryType stableType) {
        return appendWithEventKey(role, content, stableType, null);
    }

    /** Returns an immutable copy of the current entries. */
    public List<ConversationHistoryEntry> entries() {
        return List.copyOf(entries);
    }

    /**
     * Reconstruct a history from persisted state.
     *
     * <p>This bypasses {@link #append} so that original sequence numbers and
     * ordering are preserved exactly. The {@code seenEventKeys} set is
     * rebuilt from persisted entries that carry non-null event keys.
     * Callers are responsible for ensuring the entry list and nextSequence
     * are consistent.
     */
    public static ConversationHistory fromPersisted(List<ConversationHistoryEntry> persistedEntries,
                                                    long persistedNextSequence) {
        ConversationHistory history = new ConversationHistory();
        if (persistedEntries != null) {
            history.entries.addAll(persistedEntries);
            for (ConversationHistoryEntry e : persistedEntries) {
                if (e.eventKey() != null) {
                    history.seenEventKeys.add(e.eventKey());
                }
            }
        }
        history.nextSequence = persistedNextSequence;
        return history;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public long nextSequence() {
        return nextSequence;
    }

    @Override
    public String toString() {
        return "ConversationHistory{size=" + entries.size() + ", nextSeq=" + nextSequence + '}';
    }
}
