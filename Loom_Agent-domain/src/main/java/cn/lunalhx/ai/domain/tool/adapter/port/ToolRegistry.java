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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final AtomicReference<Map<String, AgentTool>> tools = new AtomicReference<>(Map.of());
    private final ToolSchemaValidator schemaValidator;

    public ToolRegistry(Collection<AgentTool> tools, ToolSchemaValidator schemaValidator) {
        this.schemaValidator = schemaValidator;
        replace(tools);
    }

    public void replace(Collection<AgentTool> replacements) {
        tools.set(validateSnapshot(replacements));
    }

    public Map<String, AgentTool> validateSnapshot(Collection<AgentTool> replacements) {
        Map<String, AgentTool> validated = new TreeMap<>();
        for (AgentTool tool : replacements) {
            String name = tool.spec().getName();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("工具名不能为空");
            }
            if (validated.containsKey(name)) {
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
            validated.put(name, tool);
        }
        return Collections.unmodifiableMap(new TreeMap<>(validated));
    }

    public List<ToolSpec> specs() {
        return tools.get().values().stream().map(AgentTool::spec).collect(Collectors.toUnmodifiableList());
    }

    public List<AgentTool> tools() {
        return List.copyOf(tools.get().values());
    }

    public boolean contains(String name) {
        return tools.get().containsKey(name);
    }

    public ToolInputValidationResult validateInput(String toolName, JsonNode input) {
        AgentTool tool = tools.get().get(toolName);
        if (tool == null) {
            return ToolInputValidationResult.failure(List.of(
                    new ToolInputValidationResult.FieldError("", "unknown_tool", "未知工具：" + toolName)
            ));
        }
        return schemaValidator.validate(toolName, tool.validationSchema(), input);
    }

    public ToolResult call(ToolCall call) {
        if (!skillAllows(call)) {
            return ToolResult.failure("skill_tool_not_allowed",
                    "当前 Skill 不允许调用工具：" + call.getName(), 0L);
        }
        AgentTool tool = tools.get().get(call.getName());
        if (tool == null) {
            return ToolResult.failure("unknown_tool", "未知工具：" + call.getName(), 0L);
        }
        return tool.call(call);
    }

    public ToolPolicyDecision policy(ToolCall call) {
        if (!skillAllows(call)) {
            return ToolPolicyDecision.highRiskDeny(
                    "当前 Skill 不允许调用工具：" + call.getName(), call.getName());
        }
        AgentTool tool = tools.get().get(call.getName());
        if (tool == null) {
            return ToolPolicyDecision.highRiskDeny("未知工具：" + call.getName(), call.getName());
        }
        return tool.policy(call);
    }

    private boolean skillAllows(ToolCall call) {
        return call == null || !Boolean.TRUE.equals(call.getSkillToolRestrictionActive())
                || call.getAllowedToolNames() != null && call.getAllowedToolNames().contains(call.getName());
    }

}
