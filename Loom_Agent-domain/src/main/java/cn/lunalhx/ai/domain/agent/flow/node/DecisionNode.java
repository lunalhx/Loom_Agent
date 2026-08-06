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
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Parses Loom XML tool/final/retry decisions. This node performs no tool
 * authorization, no input validation, no {@link ToolResult} construction and
 * no tool-step counting — those belong to {@code tool_input} and
 * {@code tool_execute}. Unknown tools, invalid parameters and parse errors
 * produce a structured {@link AgentDecision} routed to {@code tool_input} (or
 * a format-retry for parse errors), never a raw tool result.
 */
public class DecisionNode extends AbstractAgentNode {

    private final ObjectMapper objectMapper;
    private final AgentRuntimeProperties properties;
    private final ConversationHistoryAppendService ledgerAppendService;
    private final DecisionParser decisionParser;

    public DecisionNode(ObjectMapper objectMapper,
                        AgentRuntimeProperties properties,
                        ConversationHistoryAppendService ledgerAppendService) {
        super(AgentNodeNames.DECISION, List.of("modelOutput", "parseErrors", "decision"));
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.ledgerAppendService = ledgerAppendService;
        this.decisionParser = new DecisionParser(objectMapper);
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        try {
            context.setDecision(null);
            AgentDecision decision = decisionParser.parse(context.getModelOutput());
            context.setDecision(decision);
            context.setParseErrors(0);
            String type = decision.getType();
            if ("final".equals(type)) {
                return NodeResult.complete(List.of());
            }
            if ("retry".equals(type)) {
                return formatRetry(context, decision);
            }
            return NodeResult.nextNode(AgentNodeNames.TOOL_INPUT, List.of());
        } catch (DecisionParseException e) {
            return handleParseError(context, e);
        } catch (Exception e) {
            return handleParseError(context, new DecisionParseException(
                    DecisionParseErrorCode.INVALID_JSON,
                    "模型输出解析异常: " + e.getMessage(),
                    truncateModelOutput(context)));
        }
    }

    private NodeResult formatRetry(AgentContext context, AgentDecision decision) {
        context.setToolResult(ToolResult.failure("parse_error",
                decision.getAnswer() == null ? "Runtime notice: model returned malformed tool output" : decision.getAnswer(),
                0L));
        return NodeResult.nextRound(List.of());
    }

    private NodeResult handleParseError(AgentContext context, DecisionParseException e) {
        context.setParseErrors(context.getParseErrors() + 1);
        String repairMsg = e.toModelMessage();
        String guidance = "Reply with a valid <tool> call or a non-empty <final> answer. "
                + "For multi-line files, prefer <tool name=\"write_file\" path=\"file.py\"><content>...</content></tool>.";
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
}
