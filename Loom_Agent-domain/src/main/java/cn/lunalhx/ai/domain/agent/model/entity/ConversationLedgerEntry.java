package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.LedgerStableType;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable entry in the {@link ConversationLedger}.
 *
 * <p>Each entry carries at least a role, content, and stable type suitable for
 * idempotency checks and diagnostics. The sequence is assigned by the ledger
 * on append.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ConversationLedgerEntry {

    private final String entryId;
    private final long sequence;
    private final String role;
    private final String content;
    private final LedgerStableType stableType;

    private ConversationLedgerEntry(Builder builder) {
        this.entryId = builder.entryId;
        this.sequence = builder.sequence;
        this.role = Objects.requireNonNull(builder.role, "role must not be null");
        this.content = Objects.requireNonNull(builder.content, "content must not be null");
        this.stableType = Objects.requireNonNull(builder.stableType, "stableType must not be null");
    }

    @JsonCreator
    public ConversationLedgerEntry(
            @JsonProperty("entryId") String entryId,
            @JsonProperty("sequence") long sequence,
            @JsonProperty("role") String role,
            @JsonProperty("content") String content,
            @JsonProperty("stableType") LedgerStableType stableType) {
        this.entryId = entryId != null ? entryId : UUID.randomUUID().toString();
        this.sequence = sequence;
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.stableType = Objects.requireNonNull(stableType, "stableType must not be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    public String entryId() { return entryId; }
    public long sequence() { return sequence; }
    public String role() { return role; }
    public String content() { return content; }
    public LedgerStableType stableType() { return stableType; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConversationLedgerEntry that)) return false;
        return sequence == that.sequence
                && entryId.equals(that.entryId)
                && role.equals(that.role)
                && content.equals(that.content)
                && stableType == that.stableType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(entryId, sequence, role, content, stableType);
    }

    @Override
    public String toString() {
        return "ConversationLedgerEntry{entryId='" + entryId + "', seq=" + sequence
                + ", role='" + role + "', stableType=" + stableType + '}';
    }

    public static final class Builder {
        private String entryId;
        private long sequence;
        private String role;
        private String content;
        private LedgerStableType stableType;

        public Builder entryId(String v) { this.entryId = v; return this; }
        public Builder sequence(long v) { this.sequence = v; return this; }
        public Builder role(String v) { this.role = v; return this; }
        public Builder content(String v) { this.content = v; return this; }
        public Builder stableType(LedgerStableType v) { this.stableType = v; return this; }

        public ConversationLedgerEntry build() {
            if (entryId == null) {
                entryId = UUID.randomUUID().toString();
            }
            return new ConversationLedgerEntry(this);
        }
    }
}
