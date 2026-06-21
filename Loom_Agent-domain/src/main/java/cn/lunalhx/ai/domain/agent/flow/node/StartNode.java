package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;

import java.util.List;

public class StartNode extends AbstractAgentNode {

    public StartNode() {
        super(AgentNodeNames.START, List.of("requestId", "conversationId", "maxSteps"));
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        return NodeResult.next(AgentNodeNames.PLANNER, List.of(event(context, AgentEventType.META)
                .stepCount(context.getMaxSteps())
                .build()));
    }

}
