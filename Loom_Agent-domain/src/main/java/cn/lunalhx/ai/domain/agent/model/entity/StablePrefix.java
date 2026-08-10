package cn.lunalhx.ai.domain.agent.model.entity;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Frozen content prefix and its signatures, rebuilt when any signature changes.
 *
 * <p>The {@code frozenContent}/{@code fingerprint} fields remain the primary
 * payload. The additional {@code workspaceFingerprint}, {@code toolSignature}
 * and {@code runtimeSignature} let the runtime reuse an existing prefix unless
 * one of the three stable inputs actually changed:
 * <ul>
 *   <li>{@code workspaceFingerprint} — structural workspace identity (cwd, repo
 *       root, branch, default branch). Ordinary git status or doc churn does
 *       NOT invalidate it.</li>
 *   <li>{@code toolSignature} — deterministic hash of the sorted tool catalog
 *       (schema, description, capability and approval metadata).</li>
 *   <li>{@code runtimeSignature} — deterministic hash of execution constraints
 *       (main/delegate identity, path scope).</li>
 * </ul>
 *
 * <p>This is an immutable snapshot — every rebuild produces a new instance.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class StablePrefix {

    private final String frozenContent;
    private final String fingerprint;
    private final String workspaceFingerprint;
    private final String toolSignature;
    private final String runtimeSignature;
    private final long builtAt;

    @JsonCreator
    public StablePrefix(@JsonProperty("frozenContent") String frozenContent,
                        @JsonProperty("fingerprint") String fingerprint,
                        @JsonProperty("workspaceFingerprint") String workspaceFingerprint,
                        @JsonProperty("toolSignature") String toolSignature,
                        @JsonProperty("runtimeSignature") String runtimeSignature,
                        @JsonProperty("builtAt") Long builtAt) {
        this.frozenContent = Objects.requireNonNull(frozenContent, "frozenContent must not be null");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        this.workspaceFingerprint = workspaceFingerprint;
        this.toolSignature = toolSignature;
        this.runtimeSignature = runtimeSignature;
        this.builtAt = builtAt == null ? System.currentTimeMillis() : builtAt;
    }

    public String frozenContent() { return frozenContent; }
    public String fingerprint() { return fingerprint; }
    public String workspaceFingerprint() { return workspaceFingerprint; }
    public String toolSignature() { return toolSignature; }
    public String runtimeSignature() { return runtimeSignature; }
    public long builtAt() { return builtAt; }

    /** True when the prefix is a legacy two-field snapshot lacking the new signatures. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isLegacyTwoField() {
        return workspaceFingerprint == null || toolSignature == null || runtimeSignature == null;
    }

    /** True when all three stable inputs match another prefix (so it can be reused). */
    public boolean matches(StablePrefix other) {
        if (other == null) {
            return false;
        }
        return Objects.equals(workspaceFingerprint, other.workspaceFingerprint)
                && Objects.equals(toolSignature, other.toolSignature)
                && Objects.equals(runtimeSignature, other.runtimeSignature);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StablePrefix that)) return false;
        return builtAt == that.builtAt
                && frozenContent.equals(that.frozenContent)
                && fingerprint.equals(that.fingerprint)
                && Objects.equals(workspaceFingerprint, that.workspaceFingerprint)
                && Objects.equals(toolSignature, that.toolSignature)
                && Objects.equals(runtimeSignature, that.runtimeSignature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(frozenContent, fingerprint, workspaceFingerprint,
                toolSignature, runtimeSignature, builtAt);
    }

    @Override
    public String toString() {
        return "StablePrefix{fingerprint='" + fingerprint + "'}";
    }
}
