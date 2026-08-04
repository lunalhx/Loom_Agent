package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable description of the agent loop node graph.
 */
public record AgentFlowDefinition(
        Map<String, AgentNode> nodes,
        AgentHookRegistry hookRegistry,
        List<ToolSpec> toolSpecs
) {
    public AgentFlowDefinition {
        Objects.requireNonNull(nodes, "nodes must not be null");
        Objects.requireNonNull(hookRegistry, "hookRegistry must not be null");
        Objects.requireNonNull(toolSpecs, "toolSpecs must not be null");
        nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        toolSpecs = List.copyOf(toolSpecs);
    }
}
