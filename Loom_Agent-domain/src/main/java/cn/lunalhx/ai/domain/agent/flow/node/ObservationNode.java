package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.tool.model.ToolResult;

import java.util.List;

public class ObservationNode extends AbstractAgentNode {

    public ObservationNode() {
        super(AgentNodeNames.OBSERVATION, List.of("toolResult", "decision", "step"));
    }

    @Override
    public NodeResult execute(AgentContext context) {
        ToolResult result = context.getToolResult();
        appendStep(context, result != null && result.isSuccess());
        return NodeResult.next(AgentNodeNames.RENDER_PROMPT, observationEvents(context));
    }

}
