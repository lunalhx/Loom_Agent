package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;

import java.util.List;

public class PlannerNode extends AbstractAgentNode {

    public PlannerNode() {
        super(AgentNodeNames.PLANNER, List.of("question", "plan"));
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        if (context.getPlan() == null) {
            context.setPlan(AgentPlan.forQuestion(context.getQuestion()));
        }
        AgentEvent event = event(context, AgentEventType.PLAN_UPDATED)
                .plan(context.getPlan().toView())
                .build();
        return NodeResult.next(AgentNodeNames.RENDER_PROMPT, List.of(event));
    }

}
