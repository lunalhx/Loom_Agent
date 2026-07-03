package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.LedgerStableType;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Append-only conversation ledger.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Entries are only added; never removed or reordered.</li>
 *   <li>{@link #entries()} returns an immutable snapshot view.</li>
 *   <li>Each entry's {@code sequence} is a monotonically increasing counter
 *       assigned by the ledger.</li>
 * </ul>
 *
 * <p>This type is independent of {@link DynamicText} — it records conversation
 * structure for idempotency checks, diagnostics, and future replay, without
 * affecting the current prompt-rendering or model-sending behaviour.
 */
public final class ConversationLedger {

    private final List<ConversationLedgerEntry> entries = new ArrayList<>();
    private long nextSequence;

    /**
     * Append an entry. Role and content are validated: neither may be null or blank.
     *
     * @return {@code this} for fluent chaining
     * @throws NullPointerException     if role, content, or stableType is null
     * @throws IllegalArgumentException if role or content is blank
     */
    public ConversationLedger append(String role, String content, LedgerStableType stableType) {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(stableType, "stableType must not be null");
        if (StringUtils.isBlank(role)) {
            throw new IllegalArgumentException("role must not be blank");
        }
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }
        ConversationLedgerEntry entry = ConversationLedgerEntry.builder()
                .sequence(nextSequence++)
                .role(role)
                .content(content)
                .stableType(stableType)
                .build();
        entries.add(entry);
        return this;
    }

    /** Returns an immutable copy of the current entries. */
    public List<ConversationLedgerEntry> entries() {
        return List.copyOf(entries);
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
