package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
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

    public ToolApprovalResolver(ToolAuthorizationService authorizationService, PermissionPrompt prompt) {
        this.authorizationService = Objects.requireNonNull(authorizationService, "authorizationService");
        this.prompt = prompt;
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
            GrantLifetime lifetime = prompt == null
                    ? null
                    : prompt.ask(pending.display(), pending.decision());
            return authorizationService.completeApproval(context, pending, lifetime);
        }
        return result;
    }
}
