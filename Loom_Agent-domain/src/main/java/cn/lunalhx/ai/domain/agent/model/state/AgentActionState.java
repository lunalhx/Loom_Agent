package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.tool.model.ToolResult;

/**
 * Mutable action state: decision, tool result, plan, and replan.
 */
public final class AgentActionState {

    private AgentDecision decision;
    private ToolResult toolResult;
    private AgentPlan plan;
    private ReplanReason replanReason;
    private String replanMessage;

    public AgentDecision decision() { return decision; }
    public ToolResult toolResult() { return toolResult; }
    public AgentPlan plan() { return plan; }
    public ReplanReason replanReason() { return replanReason; }
    public String replanMessage() { return replanMessage; }

    // -- package-private mutators --

    public void setDecision(AgentDecision v) { this.decision = v; }
    public void setToolResult(ToolResult v) { this.toolResult = v; }
    public void setPlan(AgentPlan v) { this.plan = v; }
    public void setReplanReason(ReplanReason v) { this.replanReason = v; }
    public void setReplanMessage(String v) { this.replanMessage = v; }
}
