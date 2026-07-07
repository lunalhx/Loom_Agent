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
 *
 * <p>The optional {@code eventKey} is a deterministic, caller-supplied key
 * (e.g. {@code "{runId}:{step}:{type}"}) used by the ledger to prevent
 * duplicate entries on checkpoint resume, retry, or node re-entry.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ConversationLedgerEntry {

    private final String entryId;
    private final long sequence;
    private final String role;
    private final String content;
    private final LedgerStableType stableType;
    private final String eventKey;
    private final String toolName;
    private final String artifactId;
    private final Integer originalChars;
    private final Integer renderChars;
    private final boolean compacted;

    private ConversationLedgerEntry(Builder builder) {
        this.entryId = builder.entryId;
        this.sequence = builder.sequence;
        this.role = Objects.requireNonNull(builder.role, "role must not be null");
        this.content = Objects.requireNonNull(builder.content, "content must not be null");
        this.stableType = Objects.requireNonNull(builder.stableType, "stableType must not be null");
        this.eventKey = builder.eventKey;
        this.toolName = builder.toolName;
        this.artifactId = builder.artifactId;
        this.originalChars = builder.originalChars;
        this.renderChars = builder.renderChars;
        this.compacted = builder.compacted;
    }

    @JsonCreator
    public ConversationLedgerEntry(
            @JsonProperty("entryId") String entryId,
            @JsonProperty("sequence") long sequence,
            @JsonProperty("role") String role,
            @JsonProperty("content") String content,
            @JsonProperty("stableType") LedgerStableType stableType,
            @JsonProperty("eventKey") String eventKey,
            @JsonProperty("toolName") String toolName,
            @JsonProperty("artifactId") String artifactId,
            @JsonProperty("originalChars") Integer originalChars,
            @JsonProperty("renderChars") Integer renderChars,
            @JsonProperty("compacted") boolean compacted) {
        this.entryId = entryId != null ? entryId : UUID.randomUUID().toString();
        this.sequence = sequence;
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.stableType = Objects.requireNonNull(stableType, "stableType must not be null");
        this.eventKey = eventKey;
        this.toolName = toolName;
        this.artifactId = artifactId;
        this.originalChars = originalChars;
        this.renderChars = renderChars;
        this.compacted = compacted;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String entryId() { return entryId; }
    public long sequence() { return sequence; }
    public String role() { return role; }
    public String content() { return content; }
    public LedgerStableType stableType() { return stableType; }
    public String eventKey() { return eventKey; }
    public String toolName() { return toolName; }
    public String artifactId() { return artifactId; }
    public Integer originalChars() { return originalChars; }
    public Integer renderChars() { return renderChars; }
    public boolean compacted() { return compacted; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConversationLedgerEntry that)) return false;
        return sequence == that.sequence
                && compacted == that.compacted
                && entryId.equals(that.entryId)
                && role.equals(that.role)
                && content.equals(that.content)
                && stableType == that.stableType
                && Objects.equals(eventKey, that.eventKey)
                && Objects.equals(toolName, that.toolName)
                && Objects.equals(artifactId, that.artifactId)
                && Objects.equals(originalChars, that.originalChars)
                && Objects.equals(renderChars, that.renderChars);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entryId, sequence, role, content, stableType, eventKey,
                toolName, artifactId, originalChars, renderChars, compacted);
    }

    @Override
    public String toString() {
        return "ConversationLedgerEntry{entryId='" + entryId + "', seq=" + sequence
                + ", role='" + role + "', stableType=" + stableType
                + (eventKey != null ? ", eventKey='" + eventKey + '\'' : "")
                + (toolName != null ? ", toolName='" + toolName + '\'' : "")
                + (artifactId != null ? ", artifactId='" + artifactId + '\'' : "")
                + ", compacted=" + compacted
                + '}';
    }

    public static final class Builder {
        private String entryId;
        private long sequence;
        private String role;
        private String content;
        private LedgerStableType stableType;
        private String eventKey;
        private String toolName;
        private String artifactId;
        private Integer originalChars;
        private Integer renderChars;
        private boolean compacted;

        public Builder entryId(String v) { this.entryId = v; return this; }
        public Builder sequence(long v) { this.sequence = v; return this; }
        public Builder role(String v) { this.role = v; return this; }
        public Builder content(String v) { this.content = v; return this; }
        public Builder stableType(LedgerStableType v) { this.stableType = v; return this; }
        public Builder eventKey(String v) { this.eventKey = v; return this; }
        public Builder toolName(String v) { this.toolName = v; return this; }
        public Builder artifactId(String v) { this.artifactId = v; return this; }
        public Builder originalChars(Integer v) { this.originalChars = v; return this; }
        public Builder renderChars(Integer v) { this.renderChars = v; return this; }
        public Builder compacted(boolean v) { this.compacted = v; return this; }

        public ConversationLedgerEntry build() {
            if (entryId == null) {
                entryId = UUID.randomUUID().toString();
            }
            return new ConversationLedgerEntry(this);
        }
    }
}
