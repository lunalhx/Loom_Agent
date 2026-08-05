package cn.lunalhx.ai.domain.agent.flow;

import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Typed node execution result interpreted by the main loop.
 *
 * <p>The {@link AgentLoopPhase} drives loop control; {@code nextNode} is only
 * meaningful for {@link AgentLoopPhase#NEXT_NODE NEXT_NODE}.
 */
@Getter
public final class NodeResult {

    private final AgentLoopPhase phase;
    private final String nextNode;
    private final List<AgentEvent> events;

    private NodeResult(AgentLoopPhase phase, String nextNode, List<AgentEvent> events) {
        this.phase = Objects.requireNonNull(phase, "phase must not be null");
        this.nextNode = nextNode;
        this.events = events == null ? Collections.emptyList() : List.copyOf(events);
    }

    public static NodeResult nextNode(String nextNode, List<AgentEvent> events) {
        return new NodeResult(AgentLoopPhase.NEXT_NODE, nextNode, events);
    }

    public static NodeResult nextRound(List<AgentEvent> events) {
        return new NodeResult(AgentLoopPhase.NEXT_ROUND, null, events);
    }

    public static NodeResult complete(List<AgentEvent> events) {
        return new NodeResult(AgentLoopPhase.COMPLETE, null, events);
    }

    public static NodeResult pauseApproval(List<AgentEvent> events) {
        return new NodeResult(AgentLoopPhase.PAUSE_APPROVAL, null, events);
    }

    public static NodeResult pauseUserInput(List<AgentEvent> events) {
        return new NodeResult(AgentLoopPhase.PAUSE_USER_INPUT, null, events);
    }

    public static NodeResult fail(List<AgentEvent> events) {
        return new NodeResult(AgentLoopPhase.FAIL, null, events);
    }

    public boolean isTerminal() {
        return phase != AgentLoopPhase.NEXT_NODE && phase != AgentLoopPhase.NEXT_ROUND;
    }
}