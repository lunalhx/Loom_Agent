package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;

import java.util.List;

public class FailNode extends AbstractAgentNode {

    public FailNode() {
        super(AgentNodeNames.FAIL, List.of("stopReason", "errorCode", "errorMessage", "step"));
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        return NodeResult.terminal(List.of(
                event(context, AgentEventType.ERROR)
                        .code(context.getErrorCode())
                        .message(context.getErrorMessage())
                        .checkpointVersion(context.getCheckpointVersion())
                        .recoverable(context.getCheckpointVersion() != null)
                        .build()));
    }

}
