package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import java.util.List;

public class PlannerNode extends AbstractAgentNode {

    public PlannerNode() {
        super(AgentNodeNames.PLANNER, List.of("question", "plan"));
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        // A plan is created only when the model explicitly calls todo_write.
        // Simple tasks continue directly without emitting a misleading plan_updated event.
        return NodeResult.next(AgentNodeNames.RENDER_PROMPT, List.of());
    }

}
