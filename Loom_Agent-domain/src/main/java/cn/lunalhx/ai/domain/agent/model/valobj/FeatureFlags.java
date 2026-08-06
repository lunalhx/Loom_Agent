package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;

/**
 * Feature flags for capabilities actually implemented in the runtime.
 * Every flag listed here has a real runtime effect — no flag is advertised
 * for a capability that does nothing. Defaults match the production behavior.
 */
@Data
public class FeatureFlags {

    /** Semantic session/checkpoint resume (Phase 1). */
    private Boolean sessionResume = true;

    /** Real read-only delegate child runs with lineage (Phase 1). */
    private Boolean delegateChildRuns = true;

    /** Interactive/non-interactive tool approval gate (Phase 1). */
    private Boolean approvalGate = true;

    /** Secret redaction on all persisted artifacts (Phase 1). */
    private Boolean secretRedaction = true;

    /** Workspace durable memory promotion + relevant memory section (Phase 2). */
    private Boolean durableMemory = true;

    /** Stable prefix workspace fingerprint includes git status/commits/docs (Phase 1). */
    private Boolean stablePrefixWorkspaceFacts = true;

    /** Prompt cache metadata on the gateway (Phase 4). */
    private Boolean promptCache = true;

    public boolean sessionResume() { return Boolean.TRUE.equals(sessionResume); }
    public boolean delegateChildRuns() { return Boolean.TRUE.equals(delegateChildRuns); }
    public boolean approvalGate() { return Boolean.TRUE.equals(approvalGate); }
    public boolean secretRedaction() { return Boolean.TRUE.equals(secretRedaction); }
    public boolean durableMemory() { return Boolean.TRUE.equals(durableMemory); }
    public boolean stablePrefixWorkspaceFacts() { return Boolean.TRUE.equals(stablePrefixWorkspaceFacts); }
    public boolean promptCache() { return Boolean.TRUE.equals(promptCache); }
}
