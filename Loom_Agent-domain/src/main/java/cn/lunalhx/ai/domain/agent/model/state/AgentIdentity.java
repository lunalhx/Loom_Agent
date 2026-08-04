package cn.lunalhx.ai.domain.agent.model.state;

/**
 * Immutable run identity. Set once at context construction via {@code with*} copy methods.
 *
 * <p>Holds run lineage ({@code parentRunId}/{@code rootRunId}/{@code depth}) for
 * delegate child runs and replay; multi-role and child-ordinal fields are removed.
 */
public final class AgentIdentity {

    private final String runId;
    private final String parentRunId;
    private final String rootRunId;
    private final String requestId;
    private final String conversationId;
    private final int agentDepth;

    public AgentIdentity(String runId, String parentRunId, String rootRunId,
                         String requestId, String conversationId,
                         int agentDepth) {
        this.runId = runId;
        this.parentRunId = parentRunId;
        this.rootRunId = rootRunId;
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.agentDepth = agentDepth;
    }

    public String runId() { return runId; }
    public String parentRunId() { return parentRunId; }
    public String rootRunId() { return rootRunId; }
    public String requestId() { return requestId; }
    public String conversationId() { return conversationId; }
    public int agentDepth() { return agentDepth; }

    public AgentIdentity withRunId(String v) { return new AgentIdentity(v, parentRunId, rootRunId, requestId, conversationId, agentDepth); }
    public AgentIdentity withParentRunId(String v) { return new AgentIdentity(runId, v, rootRunId, requestId, conversationId, agentDepth); }
    public AgentIdentity withRootRunId(String v) { return new AgentIdentity(runId, parentRunId, v, requestId, conversationId, agentDepth); }
    public AgentIdentity withRequestId(String v) { return new AgentIdentity(runId, parentRunId, rootRunId, v, conversationId, agentDepth); }
    public AgentIdentity withConversationId(String v) { return new AgentIdentity(runId, parentRunId, rootRunId, requestId, v, agentDepth); }
    public AgentIdentity withAgentDepth(int v) { return new AgentIdentity(runId, parentRunId, rootRunId, requestId, conversationId, v); }
}
