package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.tool.model.ToolResult;

import java.util.List;

public class ObservationNode extends AbstractAgentNode {

    public ObservationNode() {
        super(AgentNodeNames.OBSERVATION, List.of("toolResult", "decision", "step", "dynamicText"));
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        ToolResult result = context.getToolResult();
        appendStep(context, result != null && result.isSuccess());
        context.getDynamicText().appendToolResult(
                Math.max(1, context.getStep()),
                name(),
                context.getDecision(),
                result,
                toDynamicObservation(context, result));
        return NodeResult.next(AgentNodeNames.REPLAN_GUARD, observationEvents(context));
    }

    private String toDynamicObservation(AgentContext context, ToolResult result) {
        StringBuilder text = new StringBuilder();
        text.append("Success: ").append(result != null && result.isSuccess()).append('\n');
        text.append("Observation:\n").append(result == null ? "" : result.getObservation());
        return text.toString();
    }

}
