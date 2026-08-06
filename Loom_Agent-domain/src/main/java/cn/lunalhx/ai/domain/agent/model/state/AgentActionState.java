package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;

/**
 * Mutable action state: decision, pending tool call, and tool result.
 */
public final class AgentActionState {

    private AgentDecision decision;
    private ToolCall toolCall;
    private ToolResult toolResult;

    public AgentDecision decision() { return decision; }
    public ToolCall toolCall() { return toolCall; }
    public ToolResult toolResult() { return toolResult; }

    public void setDecision(AgentDecision v) { this.decision = v; }
    public void setToolCall(ToolCall v) { this.toolCall = v; }
    public void setToolResult(ToolResult v) { this.toolResult = v; }
}
