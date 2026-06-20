package cn.lunalhx.ai.domain.tool.adapter.port;

import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(Collection<AgentTool> tools) {
        for (AgentTool tool : tools) {
            String name = tool.spec().getName();
            if (this.tools.containsKey(name)) {
                throw new IllegalStateException("重复的工具名：" + name);
            }
            this.tools.put(name, tool);
        }
    }

    public List<ToolSpec> specs() {
        return tools.values().stream().map(AgentTool::spec).collect(Collectors.toList());
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public ToolResult call(ToolCall call) {
        AgentTool tool = tools.get(call.getName());
        if (tool == null) {
            return ToolResult.failure("unknown_tool", "未知工具：" + call.getName(), 0L);
        }
        return tool.call(call);
    }

}
