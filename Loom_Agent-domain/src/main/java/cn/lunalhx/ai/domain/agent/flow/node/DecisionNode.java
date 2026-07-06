package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerInitializer;
import cn.lunalhx.ai.domain.agent.service.ledger.ControlUpdateTexts;
import cn.lunalhx.ai.domain.agent.service.subagent.SubAgentToolSpecs;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolInputValidationResult;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class DecisionNode extends AbstractAgentNode {

    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final AgentRuntimeProperties properties;
    private final ConversationLedgerAppendService ledgerAppendService;
    private final DecisionParser decisionParser;
    private volatile Schema spawnAgentsSchema;

    public DecisionNode(ObjectMapper objectMapper, ToolRegistry toolRegistry, AgentRuntimeProperties properties) {
        this(objectMapper, toolRegistry, properties, null);
    }

    public DecisionNode(ObjectMapper objectMapper, ToolRegistry toolRegistry,
                       AgentRuntimeProperties properties,
                       ConversationLedgerAppendService ledgerAppendService) {
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
                return NodeResult.next(AgentNodeNames.FINAL_ANSWER, List.of());
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
        int maxAttempts = properties.getParseErrorMaxAttempts();
        if (context.getParseErrors() > maxAttempts) {
            fail(context, AgentStopReason.PARSE_ERROR, "parse_error",
                    "模型连续返回非法 JSON (" + context.getParseErrors() + " 次)，已停止。最后错误: " + e.getMessage());
            return NodeResult.next(AgentNodeNames.FAIL, List.of());
        }
        // Build a repair-focused error message for the model, varying on repeat
        String repairMsg = e.toModelMessage();
        String guidance;
        if (context.getParseErrors() == 1) {
            guidance = "请只输出一个合法的 action 或 final JSON 对象。";
        } else {
            // Vary the repair hint on repeated errors per PLAN.md §4.1
            guidance = "你已经连续 " + context.getParseErrors() + " 次输出非法 JSON。"
                    + "请检查并修复以下问题后重试：确保输出是纯 JSON（不含 markdown 代码块或额外文字），"
                    + "type 必须是 \"action\" 或 \"final\"，所有字符串必须用双引号。"
                    + "示例: {\"type\":\"action\",\"tool\":\"read_file\",\"input\":{\"path\":\"src/App.java\"}}";
        }
        context.setToolResult(ToolResult.failure("parse_error",
                repairMsg + "\n" + guidance, 0L));
        appendStep(context, false);
        context.getDynamicText().appendSystemNote(
                Math.max(1, context.getStep()),
                name(),
                "Model Output Parse Error [" + e.getErrorCode().name() + "]",
                "Attempt " + context.getParseErrors() + "/" + (maxAttempts + 1) + "\n"
                        + "Error: " + e.getMessage() + "\n"
                        + "RawOutput:\n" + truncateModelOutput(context));
        appendParseErrorToLedger(context);
        return NodeResult.next(AgentNodeNames.RENDER_PROMPT, observationEvents(context));
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

    private void appendParseErrorToLedger(AgentContext context) {
        if (ledgerAppendService == null) {
            return;
        }
        String text = ControlUpdateTexts.renderParseErrorNote(
                context.getModelOutput(),
                context.getParseErrors(),
                properties.getParseErrorMaxAttempts());
        // Each parse error within the same step uses a distinct event key
        // (the attempt counter makes it unique)
        String eventKey = ConversationLedgerInitializer.eventKey(
                context.getRunId(),
                String.valueOf(Math.max(1, context.getStep())),
                "parse_error:" + context.getParseErrors());
        ledgerAppendService.appendControlUpdate(context, text, eventKey);
    }

}
