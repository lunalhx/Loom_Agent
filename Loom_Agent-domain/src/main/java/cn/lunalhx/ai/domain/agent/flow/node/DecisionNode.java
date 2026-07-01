package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.service.SubAgentToolSpecs;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolInputValidationResult;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DecisionNode extends AbstractAgentNode {

    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final AgentRuntimeProperties properties;
    private volatile Schema spawnAgentsSchema;

    public DecisionNode(ObjectMapper objectMapper, ToolRegistry toolRegistry, AgentRuntimeProperties properties) {
        super(AgentNodeNames.DECISION, List.of("modelOutput", "parseErrors", "registeredTools", "decision"));
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        try {
            context.setDecision(null);
            AgentDecision decision = parseDecisionJson(context.getModelOutput());
            context.setDecision(decision);
            context.setParseErrors(0);
            if ("final".equals(decision.getType())) {
                return NodeResult.next(AgentNodeNames.FINAL_ANSWER, List.of());
            }
            if (!"action".equals(decision.getType())) {
                throw new IllegalArgumentException("type 只能是 action 或 final");
            }
            if (StringUtils.isBlank(decision.getTool())) {
                throw new IllegalArgumentException("action.tool 不能为空");
            }
            // Validate tool is visible in current context's toolSpecs
            if (!isToolVisible(context, decision.getTool())) {
                return unknownTool(context, decision);
            }
            // Validate input against schema
            ToolInputValidationResult validation = validateInput(decision);
            if (!validation.valid()) {
                return invalidInput(context, decision, validation);
            }
            if (SubAgentToolSpecs.SPAWN_AGENTS.equals(decision.getTool())) {
                if (context.isSubAgentSpawnAllowed()) {
                    return NodeResult.next(AgentNodeNames.SUB_AGENT_DISPATCH, List.of());
                }
                return unavailableSubAgentTool(context, decision);
            }
            return NodeResult.next(AgentNodeNames.APPROVAL_GATE, List.of());
        } catch (Exception e) {
            context.setParseErrors(context.getParseErrors() + 1);
            if (context.getParseErrors() > properties.getParseErrorMaxAttempts()) {
                fail(context, AgentStopReason.PARSE_ERROR, "parse_error", "模型连续返回非法 JSON，已停止");
                return NodeResult.next(AgentNodeNames.FAIL, List.of());
            }
            context.setToolResult(ToolResult.failure("parse_error", "模型输出不是合法 Action JSON，请只输出 action 或 final JSON", 0L));
            appendStep(context, false);
            context.getDynamicText().appendSystemNote(
                    Math.max(1, context.getStep()),
                    name(),
                    "Model Output Parse Error",
                    "模型输出无法解析为 action/final JSON。\nRawOutput:\n" + context.getModelOutput());
            return NodeResult.next(AgentNodeNames.RENDER_PROMPT, observationEvents(context));
        }
    }

    private boolean isToolVisible(AgentContext context, String toolName) {
        if (context.getToolSpecs() == null) {
            return false;
        }
        return context.getToolSpecs().stream()
                .anyMatch(spec -> spec.getName().equals(toolName));
    }

    private ToolInputValidationResult validateInput(AgentDecision decision) {
        JsonNode input = decision.getInput();
        if (input == null || input.isMissingNode() || input.isNull()) {
            input = objectMapper.createObjectNode();
        }
        if (!input.isObject()) {
            return ToolInputValidationResult.failure(List.of(
                    new ToolInputValidationResult.FieldError("", "type", "input 必须是 JSON 对象")
            ));
        }
        if (SubAgentToolSpecs.SPAWN_AGENTS.equals(decision.getTool())) {
            return validateSpawnAgents(input);
        }
        return toolRegistry.validateInput(decision.getTool(), input);
    }

    private ToolInputValidationResult validateSpawnAgents(JsonNode input) {
        if (spawnAgentsSchema == null) {
            synchronized (this) {
                if (spawnAgentsSchema == null) {
                    ToolSchemaValidator validator = new ToolSchemaValidator(objectMapper);
                    spawnAgentsSchema = validator.compile(SubAgentToolSpecs.spawnAgentsSpec().getInputSchema());
                }
            }
        }
        ToolSchemaValidator validator = new ToolSchemaValidator(objectMapper);
        return validator.validateWithSchema(SubAgentToolSpecs.SPAWN_AGENTS, spawnAgentsSchema, input);
    }

    private NodeResult unknownTool(AgentContext context, AgentDecision decision) {
        context.setStep(context.getStep() + 1);
        context.setToolResult(ToolResult.failure("unknown_tool", "未知工具：" + decision.getTool(), 0L));
        appendStep(context, false);
        context.getDynamicText().appendAssistantAction(context.getStep(), name(), decision);
        context.getDynamicText().appendToolResult(
                context.getStep(),
                name(),
                decision,
                "Success: false\nObservation:\n未知工具：" + decision.getTool());
        return NodeResult.next(AgentNodeNames.REPLAN_GUARD, observationEvents(context));
    }

    private NodeResult invalidInput(AgentContext context, AgentDecision decision, ToolInputValidationResult validation) {
        String errorDetail = validation.errors().stream()
                .map(e -> e.pointer() + ": " + e.message())
                .collect(Collectors.joining("; "));
        context.setStep(context.getStep() + 1);
        context.setToolResult(ToolResult.failure("invalid_tool_input",
                "工具 " + decision.getTool() + " 参数校验失败: " + errorDetail, 0L));
        appendStep(context, false);
        context.getDynamicText().appendAssistantAction(context.getStep(), name(), decision);
        context.getDynamicText().appendToolResult(
                context.getStep(),
                name(),
                decision,
                "Success: false\nErrorCode: invalid_tool_input\nObservation:\n" + errorDetail);
        return NodeResult.next(AgentNodeNames.REPLAN_GUARD, observationEvents(context));
    }

    private NodeResult unavailableSubAgentTool(AgentContext context, AgentDecision decision) {
        context.setStep(context.getStep() + 1);
        context.setToolResult(ToolResult.failure("sub_agent_unavailable", "当前上下文不允许派生子 Agent", 0L));
        appendStep(context, false);
        context.getDynamicText().appendAssistantAction(context.getStep(), name(), decision);
        context.getDynamicText().appendToolResult(
                context.getStep(),
                name(),
                decision,
                "Success: false\nObservation:\n当前上下文不允许派生子 Agent");
        return NodeResult.next(AgentNodeNames.REPLAN_GUARD, observationEvents(context));
    }

    private AgentDecision parseDecisionJson(String output) throws Exception {
        String json = stripMarkdownFence(output);
        JsonNode root = objectMapper.readTree(json);
        String type = root.path("type").asText();
        if (StringUtils.isBlank(type)) {
            throw new IllegalArgumentException("type 不能为空");
        }
        JsonNode input = root.path("input");
        Map<String, Object> inputView = input.isMissingNode() || input.isNull()
                ? Map.of()
                : objectMapper.convertValue(input, new TypeReference<Map<String, Object>>() {
        });
        String rawReason = root.path("reason").asText(null);
        String reason = null;
        if (StringUtils.isNotBlank(rawReason)) {
            String trimmed = rawReason.trim();
            reason = trimmed.length() > 240 ? trimmed.substring(0, 240) : trimmed;
        }
        return AgentDecision.builder()
                .type(type)
                .thought(root.path("thought").asText(null))
                .reason(reason)
                .tool(root.path("tool").asText(null))
                .input(input)
                .inputView(inputView)
                .answer(root.path("answer").asText(null))
                .evidence(root.path("evidence").isArray()
                        ? objectMapper.convertValue(root.path("evidence"), new TypeReference<List<Map<String, Object>>>() {
                })
                        : List.of())
                .build();
    }

    private String stripMarkdownFence(String output) {
        String text = StringUtils.trimToEmpty(output);
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "");
            text = text.replaceFirst("\\s*```$", "");
        }
        return text;
    }

}
