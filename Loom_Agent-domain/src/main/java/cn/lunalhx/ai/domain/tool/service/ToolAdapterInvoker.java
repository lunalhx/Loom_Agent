package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolResult;

import java.util.Objects;

/** Invokes the tool adapter only — no Agent-state projection. */
public final class ToolAdapterInvoker {
    private final ToolRegistry registry;

    public ToolAdapterInvoker(ToolRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    public ToolResult invoke(AuthorizedToolCall authorized) {
        Objects.requireNonNull(authorized, "authorized must not be null");
        return registry.call(authorized.executionCall());
    }
}
