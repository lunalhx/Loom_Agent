package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.model.GrantLifetime;
import cn.lunalhx.ai.domain.tool.model.PermissionDecision;

/** UI port for the only interactive permission decision. */
@FunctionalInterface
public interface PermissionPrompt {
    GrantLifetime ask(AuthorizationDisplay display, PermissionDecision decision);
}
