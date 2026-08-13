package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.model.GrantLifetime;
import cn.lunalhx.ai.domain.tool.model.PermissionDecision;
import cn.lunalhx.ai.domain.tool.model.ExecutionGrantRequest;

/** UI port for the only interactive permission decision. */
@FunctionalInterface
public interface PermissionPrompt {
    GrantLifetime ask(AuthorizationDisplay display, PermissionDecision decision);

    /** Separate confirmation path for an external filesystem capability. */
    default GrantLifetime askExecutionGrant(ExecutionGrantRequest request) {
        return null;
    }

    /** Re-present a durable user-input pause. Null means the user did not answer. */
    default String askUserInput(String redactedDisplay) {
        return null;
    }
}
