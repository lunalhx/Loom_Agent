package cn.lunalhx.ai.domain.agent.model.state;

/**
 * Immutable run-scoped definition set once at context construction.
 */
public final class AgentRunDefinition {

    private final String question;
    private final String pathScope;
    private final int maxSteps;
    private final int maxSegments;
    private final int maxTotalSteps;

    public AgentRunDefinition(String question, String pathScope,
                              int maxSteps, int maxSegments, int maxTotalSteps) {
        this.question = question;
        this.pathScope = pathScope;
        this.maxSteps = maxSteps;
        this.maxSegments = maxSegments;
        this.maxTotalSteps = maxTotalSteps;
    }

    public String question() { return question; }
    public String pathScope() { return pathScope; }
    public int maxSteps() { return maxSteps; }
    public int maxSegments() { return maxSegments; }
    public int maxTotalSteps() { return maxTotalSteps; }

    public AgentRunDefinition withQuestion(String v) { return new AgentRunDefinition(v, pathScope, maxSteps, maxSegments, maxTotalSteps); }
    public AgentRunDefinition withPathScope(String v) { return new AgentRunDefinition(question, v, maxSteps, maxSegments, maxTotalSteps); }
    public AgentRunDefinition withMaxSteps(int v) { return new AgentRunDefinition(question, pathScope, v, maxSegments, maxTotalSteps); }
    public AgentRunDefinition withMaxSegments(int v) { return new AgentRunDefinition(question, pathScope, maxSteps, v, maxTotalSteps); }
    public AgentRunDefinition withMaxTotalSteps(int v) { return new AgentRunDefinition(question, pathScope, maxSteps, maxSegments, v); }
}
