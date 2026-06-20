package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;

import java.util.List;

public class ObservationNode extends AbstractAgentNode {

    public ObservationNode() {
        super(AgentNodeNames.OBSERVATION, List.of("toolResult", "decision", "step", "dynamicText"));
    }

    @Override
    public NodeResult execute(AgentContext context) {
        ToolResult result = context.getToolResult();
        appendStep(context, result != null && result.isSuccess());
        context.getDynamicText().append(
                Math.max(1, context.getStep()),
                name(),
                "Observation",
                toDynamicObservation(context, result));
        return NodeResult.next(AgentNodeNames.RENDER_PROMPT, observationEvents(context));
    }

    private String toDynamicObservation(AgentContext context, ToolResult result) {
        AgentDecision decision = context.getDecision();
        StringBuilder text = new StringBuilder();
        text.append("Thought: ").append(decision == null ? "" : decision.getThought()).append('\n');
        text.append("Tool: ").append(decision == null ? "" : decision.getTool()).append('\n');
        text.append("Input: ").append(decision == null ? "" : decision.getInputView()).append('\n');
        text.append("Success: ").append(result != null && result.isSuccess()).append('\n');
        text.append("Observation:\n").append(result == null ? "" : result.getObservation());
        return text.toString();
    }

}
