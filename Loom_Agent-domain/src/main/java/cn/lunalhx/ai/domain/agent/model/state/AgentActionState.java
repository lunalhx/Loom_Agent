package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;

/**
 * Mutable action state: decision and tool result.
 */
public final class AgentActionState {

    private AgentDecision decision;
    private ToolResult toolResult;

    public AgentDecision decision() { return decision; }
    public ToolResult toolResult() { return toolResult; }

    public void setDecision(AgentDecision v) { this.decision = v; }
    public void setToolResult(ToolResult v) { this.toolResult = v; }
}
