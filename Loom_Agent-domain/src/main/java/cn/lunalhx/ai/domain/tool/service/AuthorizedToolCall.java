package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.model.EffectProfile;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.NormalizedToolCall;
import cn.lunalhx.ai.domain.tool.model.PermissionDecision;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.agent.model.entity.DelegateRequest;

/** Opaque executor input minted only by {@link ToolAuthorizationService}. */
public final class AuthorizedToolCall {
    private final ToolCall executionCall;
    private final NormalizedToolCall normalizedCall;
    private final EffectProfile effectProfile;
    private final ExecutionProfile executionProfile;
    private final ExecutionProfile baseExecutionProfile;
    private final PermissionDecision permissionDecision;
    private final String runId;
    private final String snapshotDigest;

    AuthorizedToolCall(ToolCall rawCall, NormalizedToolCall normalizedCall,
                       EffectProfile effectProfile, ExecutionProfile executionProfile,
                       ExecutionProfile baseExecutionProfile, PermissionDecision permissionDecision,
                       String runId, String snapshotDigest) {
        this.executionCall = immutableExecutionCopy(rawCall);
        this.normalizedCall = normalizedCall;
        this.effectProfile = effectProfile;
        this.executionProfile = executionProfile;
        this.baseExecutionProfile = baseExecutionProfile;
        this.permissionDecision = permissionDecision;
        this.runId = runId;
        this.snapshotDigest = snapshotDigest;
    }

    /** The executor is the only consumer of this mutable adapter type. */
    ToolCall executionCall() { return executionCall; }
    public String toolName() { return executionCall.getName(); }
    public NormalizedToolCall normalizedCall() { return normalizedCall; }
    public EffectProfile effectProfile() { return effectProfile; }
    public ExecutionProfile executionProfile() { return executionProfile; }
    public ExecutionProfile baseExecutionProfile() { return baseExecutionProfile; }
    public PermissionDecision permissionDecision() { return permissionDecision; }
    public String runId() { return runId; }
    public String snapshotDigest() { return snapshotDigest; }

    /** Attach the Runtime-created delegate request without exposing the call payload. */
    public AuthorizedToolCall withDelegateRequest(DelegateRequest request) {
        ToolCall copy = immutableExecutionCopy(executionCall);
        copy.setDelegateRequest(request);
        return new AuthorizedToolCall(copy, normalizedCall, effectProfile, executionProfile,
                baseExecutionProfile, permissionDecision, runId, snapshotDigest);
    }

    private static ToolCall immutableExecutionCopy(ToolCall source) {
        ToolCall copy = ToolCall.builder()
                .name(source.getName()).toolCallId(source.getToolCallId())
                .input(source.getInput() == null ? null : source.getInput().deepCopy())
                .workspace(source.getWorkspace()).workspaceRoot(source.getWorkspaceRoot())
                .runId(source.getRunId()).rootRunId(source.getRootRunId())
                .conversationId(source.getConversationId()).runtimeProperties(source.getRuntimeProperties())
                .secretEnvNames(source.getSecretEnvNames()).recentSummary(source.getRecentSummary())
                .collaborationMode(source.getCollaborationMode()).effectProfile(source.getEffectProfile())
                .executionProfile(source.getExecutionProfile())
                .delegateRequest(source.getDelegateRequest()).build();
        return copy;
    }
}
