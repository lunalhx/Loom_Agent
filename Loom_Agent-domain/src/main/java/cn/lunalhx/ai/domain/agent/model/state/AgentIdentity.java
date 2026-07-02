package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;

/**
 * Immutable run identity. Set once at context construction via {@code with*} copy methods.
 */
public final class AgentIdentity {

    private final String runId;
    private final String parentRunId;
    private final String rootRunId;
    private final String requestId;
    private final String conversationId;
    private final AgentRole agentRole;
    private final int agentDepth;
    private final int childOrdinal;

    public AgentIdentity(String runId, String parentRunId, String rootRunId,
                         String requestId, String conversationId,
                         AgentRole agentRole, int agentDepth, int childOrdinal) {
        this.runId = runId;
        this.parentRunId = parentRunId;
        this.rootRunId = rootRunId;
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.agentRole = agentRole;
        this.agentDepth = agentDepth;
        this.childOrdinal = childOrdinal;
    }

    public String runId() { return runId; }
    public String parentRunId() { return parentRunId; }
    public String rootRunId() { return rootRunId; }
    public String requestId() { return requestId; }
    public String conversationId() { return conversationId; }
    public AgentRole agentRole() { return agentRole; }
    public int agentDepth() { return agentDepth; }
    public int childOrdinal() { return childOrdinal; }

    public AgentIdentity withRunId(String v) { return new AgentIdentity(v, parentRunId, rootRunId, requestId, conversationId, agentRole, agentDepth, childOrdinal); }
    public AgentIdentity withParentRunId(String v) { return new AgentIdentity(runId, v, rootRunId, requestId, conversationId, agentRole, agentDepth, childOrdinal); }
    public AgentIdentity withRootRunId(String v) { return new AgentIdentity(runId, parentRunId, v, requestId, conversationId, agentRole, agentDepth, childOrdinal); }
    public AgentIdentity withRequestId(String v) { return new AgentIdentity(runId, parentRunId, rootRunId, v, conversationId, agentRole, agentDepth, childOrdinal); }
    public AgentIdentity withConversationId(String v) { return new AgentIdentity(runId, parentRunId, rootRunId, requestId, v, agentRole, agentDepth, childOrdinal); }
    public AgentIdentity withAgentRole(AgentRole v) { return new AgentIdentity(runId, parentRunId, rootRunId, requestId, conversationId, v, agentDepth, childOrdinal); }
    public AgentIdentity withAgentDepth(int v) { return new AgentIdentity(runId, parentRunId, rootRunId, requestId, conversationId, agentRole, v, childOrdinal); }
    public AgentIdentity withChildOrdinal(int v) { return new AgentIdentity(runId, parentRunId, rootRunId, requestId, conversationId, agentRole, agentDepth, v); }
}
