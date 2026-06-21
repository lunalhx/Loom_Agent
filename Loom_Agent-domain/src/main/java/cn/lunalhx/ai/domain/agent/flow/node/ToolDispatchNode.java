package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class ToolDispatchNode extends AbstractAgentNode {

    private final ToolRegistry toolRegistry;
    private final AgentRuntimeProperties properties;

    public ToolDispatchNode(ToolRegistry toolRegistry, AgentRuntimeProperties properties) {
        super(AgentNodeNames.TOOL_DISPATCH, List.of("decision.tool", "decision.input", "step"));
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        AgentDecision decision = context.getDecision();
        context.setStep(context.getStep() + 1);
        ToolResult result = toolRegistry.call(ToolCall.builder()
                .name(decision.getTool())
                .input(decision.getInput())
                .workspaceRoot(context.getResolvedWorkspace())
                .build());
        if (StringUtils.length(result.getObservation()) > properties.getObservationMaxChars()) {
            result.setObservation(StringUtils.abbreviate(result.getObservation(), properties.getObservationMaxChars()));
            result.setTruncated(true);
        }
        context.setToolResult(result);
        context.getDynamicText().appendAssistantAction(context.getStep(), name(), decision);

        List<cn.lunalhx.ai.domain.agent.model.entity.AgentEvent> events = new ArrayList<>();
        events.add(event(context, AgentEventType.THOUGHT)
                .step(context.getStep())
                .thought(decision.getThought())
                .build());
        events.add(event(context, AgentEventType.TOOL_CALL)
                .step(context.getStep())
                .thought(decision.getThought())
                .tool(decision.getTool())
                .input(decision.getInputView())
                .workspace(context.getWorkspaceDisplayName())
                .build());
        return NodeResult.next(AgentNodeNames.OBSERVATION, events);
    }

}
