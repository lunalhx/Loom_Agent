package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.flow.NodeResult;

import java.util.Objects;

/**
 * Wraps a {@link NodeResult} with the next-node name the loop should advance to.
 * Terminal results carry the originating node name as {@code nextNode}.
 */
public record AgentNodeExecution(
        NodeResult result,
        String nextNode
) {
    public AgentNodeExecution {
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(nextNode, "nextNode must not be null");
    }

    public boolean terminal() {
        return result.isTerminal();
    }
}