package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
import cn.lunalhx.ai.domain.agent.service.ledger.ControlUpdateTexts;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.skill.service.SkillActivationDecisionHandler;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Parses Loom XML tool/final/terminal/retry decisions. This node performs no tool
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
    private final SkillActivationDecisionHandler skillActivationHandler;

    public DecisionNode(ObjectMapper objectMapper,
                        AgentRuntimeProperties properties,
                        ConversationHistoryAppendService ledgerAppendService,
                        ToolRegistry toolRegistry) {
        super(AgentNodeNames.DECISION, List.of("modelOutput", "parseErrors", "decision"));
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.ledgerAppendService = ledgerAppendService;
        this.decisionParser = new DecisionParser(objectMapper);
        this.skillActivationHandler = new SkillActivationDecisionHandler(ledgerAppendService, toolRegistry);
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
            if ("plan_submission".equals(type)) {
                return NodeResult.complete(List.of());
            }
            if ("plan_deviation".equals(type)) {
                return NodeResult.complete(List.of());
            }
            if ("skill_activation".equals(type)) {
                return skillActivationHandler.apply(context, decision);
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
        String message = decision.getAnswer() == null
                ? "Runtime notice: model returned malformed tool output"
                : decision.getAnswer();
        context.setToolResult(ToolResult.failure("parse_error",
                message + "\n" + actionGuidance(context),
                0L));
        return NodeResult.nextRound(List.of());
    }

    private NodeResult handleParseError(AgentContext context, DecisionParseException e) {
        context.setParseErrors(context.getParseErrors() + 1);
        String repairMsg = e.toModelMessage();
        context.setToolResult(ToolResult.failure("parse_error",
                repairMsg + "\n" + actionGuidance(context), 0L));
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

    private String actionGuidance(AgentContext context) {
        String terminalAction = "";
        if (context.getCollaborationMode() == CollaborationMode.PLAN
                && StringUtils.isBlank(context.getParentRunId())) {
            terminalAction = ", one exact <plan_submission> JSON action";
        } else if (context.getCollaborationMode()
                == CollaborationMode.BUILD
                && StringUtils.isBlank(context.getParentRunId())
                && context.getPlanBinding() != null
                && context.getPlanBinding().isIssuedByPlanHandoff()) {
            terminalAction = ", or one exact <plan_deviation> JSON action";
        }
        return "Reply with a valid <tool> call" + terminalAction
                + ", one exact <skill_activation>{\"name\":\"skill-name\"}</skill_activation>, "
                + "or a non-empty <final> answer. "
                + "For multi-line files, prefer <tool name=\"write_file\" path=\"file.py\"><content>...</content></tool>.";
    }

    private String truncateModelOutput(AgentContext context) {
        String output = context.getModelOutput();
        if (output == null) return "";
        return output.length() > 500 ? output.substring(0, 500) + "..." : output;
    }
}
