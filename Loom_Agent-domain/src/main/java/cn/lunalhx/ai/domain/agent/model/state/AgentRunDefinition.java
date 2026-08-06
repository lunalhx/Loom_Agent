package cn.lunalhx.ai.domain.agent.model.state;

/**
 * Immutable run-scoped definition set once at context construction.
 */
public final class AgentRunDefinition {

    private final String question;
    private final String pathScope;
    private final String sessionId;
    private final String checkpointId;
    private final int maxSteps;
    private final int maxAttempts;

    public AgentRunDefinition(String question, String pathScope,
                              int maxSteps, int maxAttempts) {
        this(question, pathScope, null, null, maxSteps, maxAttempts);
    }

    public AgentRunDefinition(String question, String pathScope,
                              String sessionId, String checkpointId,
                              int maxSteps, int maxAttempts) {
        this.question = question;
        this.pathScope = pathScope;
        this.sessionId = sessionId;
        this.checkpointId = checkpointId;
        this.maxSteps = maxSteps;
        this.maxAttempts = maxAttempts;
    }

    public String question() { return question; }
    public String pathScope() { return pathScope; }
    public String sessionId() { return sessionId; }
    public String checkpointId() { return checkpointId; }
    public int maxSteps() { return maxSteps; }
    public int maxAttempts() { return maxAttempts; }

    public AgentRunDefinition withQuestion(String v) { return new AgentRunDefinition(v, pathScope, sessionId, checkpointId, maxSteps, maxAttempts); }
    public AgentRunDefinition withPathScope(String v) { return new AgentRunDefinition(question, v, sessionId, checkpointId, maxSteps, maxAttempts); }
    public AgentRunDefinition withSessionId(String v) { return new AgentRunDefinition(question, pathScope, v, checkpointId, maxSteps, maxAttempts); }
    public AgentRunDefinition withCheckpointId(String v) { return new AgentRunDefinition(question, pathScope, sessionId, v, maxSteps, maxAttempts); }
    public AgentRunDefinition withMaxSteps(int v) { return new AgentRunDefinition(question, pathScope, sessionId, checkpointId, v, maxAttempts); }
    public AgentRunDefinition withMaxAttempts(int v) { return new AgentRunDefinition(question, pathScope, sessionId, checkpointId, maxSteps, v); }
}
