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
 * 节点图装配定义（Phase 2 §二）。
 *
 * <p>不可变描述 Agent Loop 的节点集合、Hook 注册表、工具规格列表以及是否启用子 Agent。
 * 构造时对 Map/List 做防御性复制，保留节点插入顺序，校验节点名称不重复，
 * 且至少包含 {@code START} 和 {@code FAIL}。
 */
public record AgentFlowDefinition(
        Map<String, AgentNode> nodes,
        AgentHookRegistry hookRegistry,
        List<ToolSpec> toolSpecs,
        boolean subAgentAvailable
) {
    public AgentFlowDefinition {
        Objects.requireNonNull(nodes, "nodes must not be null");
        Objects.requireNonNull(hookRegistry, "hookRegistry must not be null");
        Objects.requireNonNull(toolSpecs, "toolSpecs must not be null");
        nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        toolSpecs = List.copyOf(toolSpecs);
    }
}
