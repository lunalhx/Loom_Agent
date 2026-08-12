package cn.lunalhx.ai.domain.tool.adapter.port;

import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.model.CallEffectAssessment;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolInputValidationResult;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Explicitly ordered tool registry for the seven loom-code tools.
 *
 * <p>Six base tools are registered in fixed order and {@code delegate} is
 * appended last. The registry never auto-collects arbitrary {@link AgentTool}
 * beans; it is constructed from the explicit ordered list. Unknown tools and
 * input validation follow loom-code semantics.
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    public static final List<String> BASE_TOOL_NAMES = List.of(
            "list_files", "read_file", "search", "run_shell", "write_file", "patch_file");
    public static final String DELEGATE_TOOL_NAME = "delegate";

    private final AtomicReference<Map<String, AgentTool>> tools = new AtomicReference<>(Map.of());
    private final ToolSchemaValidator schemaValidator;

    public ToolRegistry(Collection<AgentTool> tools, ToolSchemaValidator schemaValidator) {
        this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator must not be null");
        replace(tools);
    }

    public void replace(Collection<AgentTool> replacements) {
        tools.set(validateSnapshot(replacements));
    }

    public Map<String, AgentTool> validateSnapshot(Collection<AgentTool> replacements) {
        Map<String, AgentTool> validated = new LinkedHashMap<>();
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
        return Collections.unmodifiableMap(new LinkedHashMap<>(validated));
    }

    /** Root-visible specs in fixed order: six base tools then delegate. */
    public List<ToolSpec> specs() {
        return tools.get().values().stream().map(AgentTool::spec).collect(Collectors.toList());
    }

    /** Six base tools only (delegate excluded) — used by delegate child runs. */
    public List<ToolSpec> baseSpecs() {
        return tools.get().values().stream()
                .map(AgentTool::spec)
                .filter(s -> !DELEGATE_TOOL_NAME.equals(s.getName()))
                .collect(Collectors.toList());
    }

    /** Model-visible catalog projected for the immutable Run mode snapshot. */
    public List<ToolSpec> effectiveSpecs(CollaborationMode mode) {
        return effectiveSpecs(mode, null);
    }

    /** Model-visible catalog projected from mode and base-session allowlist. */
    public List<ToolSpec> effectiveSpecs(CollaborationMode mode,
                                         Collection<String> allowedTools) {
        return effectiveSpecs(mode, allowedTools, ExecutionProfile.forRun(mode, false));
    }

    /** Catalog projected against the already frozen execution capability. */
    public List<ToolSpec> effectiveSpecs(CollaborationMode mode, Collection<String> allowedTools,
                                         ExecutionProfile executionProfile) {
        CollaborationMode effectiveMode = Objects.requireNonNull(mode,
                "collaboration mode must not be null");
        return tools.get().values().stream()
                .filter(tool -> allowedTools == null || allowedTools.isEmpty()
                        || allowedTools.contains(tool.spec().getName()))
                .filter(tool -> effectiveMode != CollaborationMode.PLAN
                        || isPlanVisible(tool))
                .filter(tool -> tool.isAvailable(executionProfile))
                .map(AgentTool::spec)
                .collect(Collectors.toList());
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
        AgentTool tool = tools.get().get(call.getName());
        if (tool == null) {
            return ToolResult.failure("unknown_tool", "未知工具：" + call.getName(), 0L);
        }
        return tool.call(call);
    }

    public CallEffectAssessment assessEffect(String name, ToolCall call,
                                              ExecutionProfile executionProfile) {
        AgentTool tool = tools.get().get(name);
        return tool == null ? CallEffectAssessment.untrusted()
                : tool.assessEffect(call, executionProfile);
    }

    public boolean isAvailable(String name, ExecutionProfile executionProfile) {
        AgentTool tool = tools.get().get(name);
        return tool != null && tool.isAvailable(executionProfile);
    }

    public boolean isPlanVisible(String name) {
        AgentTool tool = tools.get().get(name);
        return tool != null && isPlanVisible(tool);
    }

    public boolean isPlanVisible(String name, Collection<String> allowedTools) {
        return (allowedTools == null || allowedTools.isEmpty() || allowedTools.contains(name))
                && isPlanVisible(name);
    }

    private boolean isPlanVisible(AgentTool tool) {
        ToolSpec spec = tool.spec();
        ExecutionProfile planProfile = ExecutionProfile.forRun(CollaborationMode.PLAN, false);
        if ("run_shell".equals(spec.getName())) return tool.isPlanCatalogVisible(planProfile);
        ToolCall probe = ToolCall.builder()
                .name(spec.getName())
                .input(JsonNodeFactory.instance.objectNode())
                .collaborationMode(CollaborationMode.PLAN)
                .build();
        CallEffectAssessment assessment;
        try {
            assessment = tool.assessEffect(probe, planProfile);
        } catch (RuntimeException e) {
            return false;
        }
        return assessment != null && assessment.trusted()
                && planProfile.allows(assessment.profile());
    }
}
