package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.model.EffectProfile;
import cn.lunalhx.ai.domain.tool.model.ExecutionGrant;
import cn.lunalhx.ai.domain.tool.model.ExecutionGrantRequest;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.NormalizedToolCall;
import cn.lunalhx.ai.domain.tool.model.PermissionDecision;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;

import java.util.List;

/** Decision-only authorization outcome. Interactive prompting happens outside this type. */
public record ToolAuthorizationResult(
        AuthorizedToolCall authorizedCall,
        ToolResult rejection,
        PendingToolApproval pendingApproval,
        PendingExecutionGrant pendingExecutionGrant) {

    public record PendingToolApproval(
            AuthorizationDisplay display,
            PermissionDecision decision,
            ToolCall call,
            NormalizedToolCall normalized,
            EffectProfile effectProfile,
            ExecutionProfile effectiveProfile,
            ExecutionProfile baseProfile,
            String runId,
            String snapshotDigest) {}

    public record PendingExecutionGrant(
            ExecutionGrantRequest request,
            ToolCall call,
            NormalizedToolCall normalized,
            ExecutionProfile baseProfile,
            List<ExecutionGrant> availableGrants,
            String runId,
            String snapshotDigest,
            ToolExecutor.ToolRuntimePolicy runtimePolicy,
            PermissionPolicySnapshot policy) {}

    public static ToolAuthorizationResult authorized(AuthorizedToolCall call) {
        return new ToolAuthorizationResult(call, null, null, null);
    }

    public static ToolAuthorizationResult rejected(ToolResult result) {
        return new ToolAuthorizationResult(null, result, null, null);
    }

    public static ToolAuthorizationResult needsApproval(PendingToolApproval pending) {
        return new ToolAuthorizationResult(null, null, pending, null);
    }

    public static ToolAuthorizationResult needsExecutionGrant(PendingExecutionGrant pending) {
        return new ToolAuthorizationResult(null, null, null, pending);
    }

    public boolean authorized() {
        return authorizedCall != null;
    }

    public boolean needsApproval() {
        return pendingApproval != null;
    }

    public boolean needsExecutionGrant() {
        return pendingExecutionGrant != null;
    }
}
