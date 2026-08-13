package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.PendingInteraction;
import cn.lunalhx.ai.domain.tool.model.GrantLifetime;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;
import cn.lunalhx.ai.domain.tool.model.ToolCall;

import java.util.Objects;

/**
 * Resolves interactive approval and execution-grant prompts around the
 * decision-only {@link ToolAuthorizationService}.
 */
public final class ToolApprovalResolver {
    private final ToolAuthorizationService authorizationService;
    private final PermissionPrompt prompt;
    private final PendingInteractionRecorder pendingRecorder;

    public ToolApprovalResolver(ToolAuthorizationService authorizationService, PermissionPrompt prompt) {
        this(authorizationService, prompt, PendingInteractionRecorder.NOOP);
    }

    public ToolApprovalResolver(ToolAuthorizationService authorizationService, PermissionPrompt prompt,
                                PendingInteractionRecorder pendingRecorder) {
        this.authorizationService = Objects.requireNonNull(authorizationService, "authorizationService");
        this.prompt = prompt;
        this.pendingRecorder = Objects.requireNonNull(pendingRecorder, "pendingRecorder");
    }

    public ToolAuthorizationResult resolve(AgentContext context, ToolCall call,
                                           ToolExecutor.ToolRuntimePolicy runtimePolicy,
                                           PermissionPolicySnapshot policy) {
        ToolAuthorizationResult result = authorizationService.authorize(context, call, runtimePolicy, policy);
        while (result.needsExecutionGrant()) {
            GrantLifetime lifetime = prompt == null
                    ? null
                    : prompt.askExecutionGrant(result.pendingExecutionGrant().request());
            result = authorizationService.continueWithExecutionGrant(
                    context, result.pendingExecutionGrant(), lifetime);
        }
        if (result.needsApproval()) {
            ToolAuthorizationResult.PendingToolApproval pending = result.pendingApproval();
            persistApproval(context, pending);
            GrantLifetime lifetime = prompt == null
                    ? null
                    : prompt.ask(pending.display(), pending.decision());
            ToolAuthorizationResult completed = authorizationService.completeApproval(context, pending, lifetime);
            clearPending(context);
            return completed;
        }
        return result;
    }

    private void persistApproval(AgentContext context, ToolAuthorizationResult.PendingToolApproval pending) {
        pendingRecorder.persistPending(context, PendingInteraction.toolApproval(
                pending.display(), pending.decision(),
                pending.normalized().permissionSubject().exactKey(),
                pending.baseProfile()));
    }

    private void clearPending(AgentContext context) {
        pendingRecorder.clearPending(context);
    }
}
