package cn.lunalhx.ai.domain.agent.model.entity;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Frozen content prefix and its fingerprint, rebuilt on each compaction or
 * prefix change.
 *
 * <p>This is an immutable snapshot — every rebuild produces a new instance.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class StablePrefix {

    private final String frozenContent;
    private final String fingerprint;

    @JsonCreator
    public StablePrefix(@JsonProperty("frozenContent") String frozenContent,
                        @JsonProperty("fingerprint") String fingerprint) {
        this.frozenContent = Objects.requireNonNull(frozenContent, "frozenContent must not be null");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint must not be null");
    }

    public String frozenContent() { return frozenContent; }
    public String fingerprint() { return fingerprint; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StablePrefix that)) return false;
        return frozenContent.equals(that.frozenContent)
                && fingerprint.equals(that.fingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(frozenContent, fingerprint);
    }

    @Override
    public String toString() {
        return "StablePrefix{fingerprint='" + fingerprint + "'}";
    }
}
