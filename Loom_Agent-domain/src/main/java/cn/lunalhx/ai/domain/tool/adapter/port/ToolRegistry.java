package cn.lunalhx.ai.domain.tool.adapter.port;

import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolInputValidationResult;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final ToolSchemaValidator schemaValidator;

    public ToolRegistry(Collection<AgentTool> tools, ToolSchemaValidator schemaValidator) {
        this.schemaValidator = schemaValidator;
        for (AgentTool tool : tools) {
            String name = tool.spec().getName();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("工具名不能为空");
            }
            if (this.tools.containsKey(name)) {
                throw new IllegalStateException("重复的工具名：" + name);
            }
            ToolSpec spec = tool.spec();
            if (spec.getDescription() == null || spec.getDescription().isBlank()) {
                throw new IllegalStateException("工具 " + name + " 的 description 不能为空");
            }
            String schema = tool.validationSchema();
            if (schema == null || schema.isBlank()) {
                throw new IllegalStateException("工具 " + name + " 的 Schema 不能为空");
            }
            schemaValidator.compile(schema);
            this.tools.put(name, tool);
        }
    }

    public List<ToolSpec> specs() {
        return tools.values().stream().map(AgentTool::spec).collect(Collectors.toList());
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public ToolInputValidationResult validateInput(String toolName, JsonNode input) {
        AgentTool tool = tools.get(toolName);
        if (tool == null) {
            return ToolInputValidationResult.failure(List.of(
                    new ToolInputValidationResult.FieldError("", "unknown_tool", "未知工具：" + toolName)
            ));
        }
        return schemaValidator.validate(toolName, tool.validationSchema(), input);
    }

    public ToolResult call(ToolCall call) {
        AgentTool tool = tools.get(call.getName());
        if (tool == null) {
            return ToolResult.failure("unknown_tool", "未知工具：" + call.getName(), 0L);
        }
        return tool.call(call);
    }

    public ToolPolicyDecision policy(ToolCall call) {
        AgentTool tool = tools.get(call.getName());
        if (tool == null) {
            return ToolPolicyDecision.highRiskDeny("未知工具：" + call.getName(), call.getName());
        }
        return tool.policy(call);
    }

}
