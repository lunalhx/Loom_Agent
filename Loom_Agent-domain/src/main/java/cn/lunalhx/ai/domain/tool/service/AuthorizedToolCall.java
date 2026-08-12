package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.model.EffectProfile;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.NormalizedToolCall;
import cn.lunalhx.ai.domain.tool.model.PermissionDecision;
import cn.lunalhx.ai.domain.tool.model.ToolCall;

/** Opaque executor input minted only by {@link ToolAuthorizationService}. */
public final class AuthorizedToolCall {
    private final ToolCall rawCall;
    private final NormalizedToolCall normalizedCall;
    private final EffectProfile effectProfile;
    private final ExecutionProfile executionProfile;
    private final PermissionDecision permissionDecision;
    private final String runId;
    private final String snapshotDigest;

    AuthorizedToolCall(ToolCall rawCall, NormalizedToolCall normalizedCall,
                       EffectProfile effectProfile, ExecutionProfile executionProfile,
                       PermissionDecision permissionDecision, String runId, String snapshotDigest) {
        this.rawCall = rawCall;
        this.normalizedCall = normalizedCall;
        this.effectProfile = effectProfile;
        this.executionProfile = executionProfile;
        this.permissionDecision = permissionDecision;
        this.runId = runId;
        this.snapshotDigest = snapshotDigest;
    }

    public ToolCall rawCall() { return rawCall; }
    public NormalizedToolCall normalizedCall() { return normalizedCall; }
    public EffectProfile effectProfile() { return effectProfile; }
    public ExecutionProfile executionProfile() { return executionProfile; }
    public PermissionDecision permissionDecision() { return permissionDecision; }
    public String runId() { return runId; }
    public String snapshotDigest() { return snapshotDigest; }
}
