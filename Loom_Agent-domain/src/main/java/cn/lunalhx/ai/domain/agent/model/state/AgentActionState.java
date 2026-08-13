package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.ToolExecutionMarker;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.service.AuthorizedToolCall;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable action state: decision, pending tool call, and tool result.
 */
public final class AgentActionState {

    private AgentDecision decision;
    private ToolCall toolCall;
    private ToolResult toolResult;
    private AuthorizedToolCall authorizedToolCall;
    private ToolExecutionMarker executionWindow;
    private List<ToolExecutionMarker> interruptedToolCalls = new ArrayList<>();

    public AgentDecision decision() { return decision; }
    public ToolCall toolCall() { return toolCall; }
    public ToolResult toolResult() { return toolResult; }
    public AuthorizedToolCall authorizedToolCall() { return authorizedToolCall; }
    public ToolExecutionMarker executionWindow() { return executionWindow; }
    public List<ToolExecutionMarker> interruptedToolCalls() {
        return interruptedToolCalls == null ? List.of() : List.copyOf(interruptedToolCalls);
    }

    public void setDecision(AgentDecision v) { this.decision = v; }
    public void setToolCall(ToolCall v) { this.toolCall = v; }
    public void setToolResult(ToolResult v) { this.toolResult = v; }
    public void setAuthorizedToolCall(AuthorizedToolCall v) { this.authorizedToolCall = v; }
    public void setExecutionWindow(ToolExecutionMarker v) { this.executionWindow = v; }
    public void setInterruptedToolCalls(List<ToolExecutionMarker> v) {
        this.interruptedToolCalls = v == null ? new ArrayList<>() : new ArrayList<>(v);
    }

    public void addInterruptedToolCall(ToolExecutionMarker marker) {
        if (marker == null) {
            return;
        }
        if (interruptedToolCalls == null) {
            interruptedToolCalls = new ArrayList<>();
        }
        interruptedToolCalls.add(marker);
    }
}
