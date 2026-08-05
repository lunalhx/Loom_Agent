package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
import cn.lunalhx.ai.domain.agent.service.ledger.ControlUpdateTexts;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolInputValidationResult;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parses final/action/retry. Unknown tools, invalid parameters, and parse
 * errors only produce a structured {@link ToolResult} and route to
 * {@link AgentNodeNames#OBSERVATION}; they never write history or Observation
 * directly — that is the sole responsibility of {@link ObservationNode}.
 */
public class DecisionNode extends AbstractAgentNode {

    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final AgentRuntimeProperties properties;
    private final ConversationHistoryAppendService ledgerAppendService;
    private final DecisionParser decisionParser;

    public DecisionNode(ObjectMapper objectMapper, ToolRegistry toolRegistry,
                        AgentRuntimeProperties properties,
                        ConversationHistoryAppendService ledgerAppendService) {
        super(AgentNodeNames.DECISION, List.of("modelOutput", "parseErrors", "registeredTools", "decision"));
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.ledgerAppendService = ledgerAppendService;
        this.decisionParser = new DecisionParser(objectMapper);
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        try {
            context.setDecision(null);
            Set<String> visibleTools = context.getToolSpecs() == null
                    ? Set.of()
                    : context.getToolSpecs().stream()
                            .map(ToolSpec::getName)
                            .collect(Collectors.toSet());
            AgentDecision decision =
                    decisionParser.parse(context.getModelOutput(), visibleTools);
            context.setDecision(decision);
            context.setParseErrors(0);
            if ("final".equals(decision.getType())) {
                return NodeResult.complete(List.of());
            }
            context.runtime().advanceToolStep(decision.getTool());
            if (!isToolVisible(context, decision.getTool())) {
                return unknownTool(context, decision);
            }
            ToolInputValidationResult validation = validateInput(decision);
            if (!validation.valid()) {
                return invalidInput(context, decision, validation);
            }
            return NodeResult.nextNode(AgentNodeNames.APPROVAL_GATE, List.of());
        } catch (DecisionParseException e) {
            return handleParseError(context, e);
        } catch (Exception e) {
            return handleParseError(context, new DecisionParseException(
                    DecisionParseErrorCode.INVALID_JSON,
                    "模型输出解析异常: " + e.getMessage(),
                    truncateModelOutput(context)));
        }
    }

    private NodeResult handleParseError(AgentContext context, DecisionParseException e) {
        context.setParseErrors(context.getParseErrors() + 1);
        String repairMsg = e.toModelMessage();
        String guidance;
        if (context.getParseErrors() == 1) {
            guidance = "请只输出一个合法的 action 或 final JSON 对象。";
        } else {
            guidance = "你已经连续 " + context.getParseErrors() + " 次输出非法 JSON。"
                    + "请检查并修复以下问题后重试：确保输出是纯 JSON（不含 markdown 代码块或额外文字），"
                    + "type 必须是 \"action\" 或 \"final\"，所有字符串必须用双引号。"
                    + "示例: {\"type\":\"action\",\"tool\":\"read_file\",\"input\":{\"path\":\"src/App.java\"}}";
        }
        context.setToolResult(ToolResult.failure("parse_error",
                repairMsg + "\n" + guidance, 0L));
        if (ledgerAppendService != null) {
            String note = ControlUpdateTexts.renderParseErrorNote(
                    truncateModelOutput(context),
                    context.getParseErrors(),
                    context.getMaxAttempts());
            String eventKey = ConversationHistoryInitializer.eventKey(context.getRunId(),
                    String.valueOf(context.getModelAttempts()), "parse_error:" + context.getParseErrors());
            ledgerAppendService.appendSystemNote(context, note, eventKey);
        }
        return NodeResult.nextRound(List.of());
    }

    private String truncateModelOutput(AgentContext context) {
        String output = context.getModelOutput();
        if (output == null) return "";
        return output.length() > 500 ? output.substring(0, 500) + "..." : output;
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
        return toolRegistry.validateInput(decision.getTool(), input);
    }

    private NodeResult unknownTool(AgentContext context, AgentDecision decision) {
        context.setToolResult(ToolResult.failure("unknown_tool", "未知工具：" + decision.getTool(), 0L));
        return NodeResult.nextNode(AgentNodeNames.OBSERVATION, List.of());
    }

    private NodeResult invalidInput(AgentContext context, AgentDecision decision, ToolInputValidationResult validation) {
        String errorDetail = validation.errors().stream()
                .map(e -> e.pointer() + ": " + e.message())
                .collect(Collectors.joining("; "));
        context.setToolResult(ToolResult.failure("invalid_tool_input",
                "工具 " + decision.getTool() + " 参数校验失败: " + errorDetail, 0L));
        return NodeResult.nextNode(AgentNodeNames.OBSERVATION, List.of());
    }
}