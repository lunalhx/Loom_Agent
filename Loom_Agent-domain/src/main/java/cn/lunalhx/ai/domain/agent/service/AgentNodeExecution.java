package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.flow.NodeResult;

public record AgentNodeExecution(
        NodeResult result,
        String nextNode
) {
    public boolean terminal() {
        return result.isTerminal();
    }
}
