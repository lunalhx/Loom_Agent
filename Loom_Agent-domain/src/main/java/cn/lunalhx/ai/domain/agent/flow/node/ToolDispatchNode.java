package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Executes one tool and generates a deterministic {@code toolCallId}.
 * Does not count tool steps (the {@link DecisionNode} already did) and
 * carries no checkpoint hook.
 */
public class ToolDispatchNode extends AbstractAgentNode {

    private final ToolRegistry toolRegistry;
    private final AgentRuntimeProperties properties;

    public ToolDispatchNode(ToolRegistry toolRegistry,
                            AgentRuntimeProperties properties) {
        super(AgentNodeNames.TOOL_DISPATCH, List.of("decision.tool", "decision.input"));
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        AgentDecision decision = context.getDecision();
        ToolCall toolCall = ToolCall.builder()
                .name(decision.getTool())
                .toolCallId(toolCallId(context, decision))
                .input(decision.getInput())
                .workspace(context.getWorkspace())
                .workspaceRoot(context.getResolvedWorkspace())
                .runId(context.getRunId())
                .rootRunId(context.getRootRunId())
                .conversationId(context.getConversationId())
                .runtimeProperties(context.runtimeProperties(properties))
                .build();

        ToolResult result = toolRegistry.call(toolCall);
        context.setToolResult(result);

        List<AgentEvent> events = new ArrayList<>();
        events.add(event(context, AgentEventType.THOUGHT)
                .thought(decision.getReason())
                .build());
        events.add(event(context, AgentEventType.TOOL_CALL)
                .thought(decision.getReason())
                .tool(decision.getTool())
                .toolCallId(toolCall.getToolCallId())
                .input(decision.getInputView())
                .workspace(context.getWorkspaceDisplayName())
                .build());
        return NodeResult.nextNode(AgentNodeNames.OBSERVATION, events);
    }

    private String toolCallId(AgentContext context, AgentDecision decision) {
        String input = decision.getInput() == null ? "" : decision.getInput().toString();
        return DigestUtils.sha256Hex(
                context.getRunId() + "|" + context.getToolSteps()
                        + "|" + decision.getTool() + "|" + input)
                .substring(0, 24);
    }
}