package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.LedgerStableType;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Append-only conversation ledger.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Entries are only added; never removed or reordered.</li>
 *   <li>{@link #entries()} returns an immutable snapshot view.</li>
 *   <li>Each entry's {@code sequence} is a monotonically increasing counter
 *       assigned by the ledger.</li>
 *   <li>Entries with a non-null {@code eventKey} are deduplicated:
 *       the same event key will not produce a second entry.</li>
 * </ul>
 *
 * <p>This type is independent of {@link DynamicText} — it records conversation
 * structure for idempotency checks, diagnostics, and future replay, without
 * affecting the current prompt-rendering or model-sending behaviour.
 */
public final class ConversationLedger {

    private final List<ConversationLedgerEntry> entries = new ArrayList<>();
    private final Set<String> seenEventKeys = new HashSet<>();
    private long nextSequence;

    /**
     * Append an entry with an event key for idempotency.
     *
     * <p>If {@code eventKey} is non-null and has already been seen by this
     * ledger, the call is silently ignored — the entry is not duplicated.
     * Null event keys bypass deduplication (backward compatibility).
     *
     * @return {@code this} for fluent chaining
     * @throws NullPointerException     if role, content, or stableType is null
     * @throws IllegalArgumentException if role is blank or content is empty
     */
    public ConversationLedger appendWithEventKey(String role, String content,
                                                  LedgerStableType stableType, String eventKey) {
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
            // Duplicate event key — idempotent no-op
            return this;
        }
        ConversationLedgerEntry entry = ConversationLedgerEntry.builder()
                .sequence(nextSequence++)
                .role(role)
                .content(content)
                .stableType(stableType)
                .eventKey(eventKey)
                .build();
        entries.add(entry);
        return this;
    }

    /**
     * Append an entry without deduplication (backward compatible).
     *
     * @return {@code this} for fluent chaining
     */
    public ConversationLedger append(String role, String content, LedgerStableType stableType) {
        return appendWithEventKey(role, content, stableType, null);
    }

    /** Returns an immutable copy of the current entries. */
    public List<ConversationLedgerEntry> entries() {
        return List.copyOf(entries);
    }

    /**
     * Reconstruct a ledger from persisted state.
     *
     * <p>This bypasses {@link #append} so that original sequence numbers and
     * ordering are preserved exactly. The {@code seenEventKeys} set is
     * rebuilt from persisted entries that carry non-null event keys.
     * Callers are responsible for ensuring the entry list and nextSequence
     * are consistent.
     */
    public static ConversationLedger fromPersisted(List<ConversationLedgerEntry> persistedEntries,
                                                    long persistedNextSequence) {
        ConversationLedger ledger = new ConversationLedger();
        if (persistedEntries != null) {
            ledger.entries.addAll(persistedEntries);
            for (ConversationLedgerEntry e : persistedEntries) {
                if (e.eventKey() != null) {
                    ledger.seenEventKeys.add(e.eventKey());
                }
            }
        }
        ledger.nextSequence = persistedNextSequence;
        return ledger;
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
        return "ConversationLedger{size=" + entries.size() + ", nextSeq=" + nextSequence + '}';
    }
}
