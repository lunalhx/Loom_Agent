package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookAction;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;

import java.util.Collections;
import java.util.List;

public record AgentNodeExecution(
        NodeResult result,
        String nextNode,
        List<AgentEvent> terminalEvents,
        AgentHookAction stopHookAction
) {
    public AgentNodeExecution(NodeResult result, String nextNode) {
        this(result, nextNode, Collections.emptyList(), null);
    }

    public static AgentNodeExecution terminalWithDeferred(NodeResult result, String nodeName, List<AgentEvent> terminalEvents) {
        return new AgentNodeExecution(result, nodeName, terminalEvents, null);
    }

    public static AgentNodeExecution stopContinued(String nextNode, AgentHookAction action) {
        return new AgentNodeExecution(null, nextNode, Collections.emptyList(), action);
    }

    public boolean terminal() {
        return result != null && result.isTerminal();
    }

    public boolean isStopContinued() {
        return stopHookAction != null && stopHookAction.getType() == AgentHookAction.Type.CONTINUE_AT_NODE;
    }

    public boolean hasDeferredTerminalEvents() {
        return terminalEvents != null && !terminalEvents.isEmpty();
    }
}
