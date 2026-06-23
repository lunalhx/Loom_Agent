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
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;

public class DecisionNode extends AbstractAgentNode {

    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final AgentRuntimeProperties properties;

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
            if (SubAgentToolSpecs.SPAWN_AGENTS.equals(decision.getTool())) {
                if (context.isSubAgentSpawnAllowed()) {
                    return NodeResult.next(AgentNodeNames.SUB_AGENT_DISPATCH, List.of());
                }
                return unavailableSubAgentTool(context, decision);
            }
            if (!toolRegistry.contains(decision.getTool())) {
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
        return AgentDecision.builder()
                .type(type)
                .thought(root.path("thought").asText(null))
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
