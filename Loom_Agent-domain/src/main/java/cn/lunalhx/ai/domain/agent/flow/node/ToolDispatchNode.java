package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookRegistry;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ToolDispatchNode extends AbstractAgentNode {

    private final ToolRegistry toolRegistry;
    private final AgentRuntimeProperties properties;
    private final AgentHookRegistry hookRegistry;
    private final ConversationHistoryAppendService ledgerAppendService;

    public ToolDispatchNode(ToolRegistry toolRegistry,
                            AgentRuntimeProperties properties,
                            AgentHookRegistry hookRegistry,
                            ConversationHistoryAppendService ledgerAppendService) {
        super(AgentNodeNames.TOOL_DISPATCH, List.of("decision.tool", "decision.input", "step"));
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry must not be null");
        this.ledgerAppendService = ledgerAppendService;
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

        context.setStep(context.getStep() + 1);

        List<AgentEvent> events = new ArrayList<>();
        events.addAll(hookRegistry.trigger(AgentHookEvent.BEFORE_TOOL, AgentHookContext.builder()
                .agentContext(context)
                .node(name())
                .toolCall(toolCall)
                .reason("before_tool:" + decision.getTool())
                .build()));

        ToolResult result = toolRegistry.call(toolCall);
        context.setToolResult(result);

        events.add(event(context, AgentEventType.THOUGHT)
                .step(context.getStep())
                .thought(decision.getThought())
                .build());
        events.add(event(context, AgentEventType.TOOL_CALL)
                .step(context.getStep())
                .thought(decision.getThought())
                .tool(decision.getTool())
                .toolCallId(toolCall.getToolCallId())
                .input(decision.getInputView())
                .workspace(context.getWorkspaceDisplayName())
                .build());
        events.addAll(hookRegistry.trigger(AgentHookEvent.AFTER_TOOL, AgentHookContext.builder()
                .agentContext(context)
                .node(name())
                .toolCall(toolCall)
                .toolResult(result)
                .reason("after_tool:" + decision.getTool())
                .build()));
        return NodeResult.next(AgentNodeNames.OBSERVATION, events);
    }

    private String toolCallId(AgentContext context, AgentDecision decision) {
        String input = decision.getInput() == null ? "" : decision.getInput().toString();
        return DigestUtils.sha256Hex(
                context.getRunId() + "|" + (context.getStep() + 1)
                        + "|" + decision.getTool() + "|" + input)
                .substring(0, 24);
    }

}
