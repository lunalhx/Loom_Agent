package cn.lunalhx.ai.domain.agent.model.state;

/**
 * Immutable run-scoped definition set once at context construction.
 */
public final class AgentRunDefinition {

    private final String question;
    private final String pathScope;
    private final int maxSteps;
    private final int maxAttempts;

    public AgentRunDefinition(String question, String pathScope,
                              int maxSteps, int maxAttempts) {
        this.question = question;
        this.pathScope = pathScope;
        this.maxSteps = maxSteps;
        this.maxAttempts = maxAttempts;
    }

    public String question() { return question; }
    public String pathScope() { return pathScope; }
    public int maxSteps() { return maxSteps; }
    public int maxAttempts() { return maxAttempts; }

    public AgentRunDefinition withQuestion(String v) { return new AgentRunDefinition(v, pathScope, maxSteps, maxAttempts); }
    public AgentRunDefinition withPathScope(String v) { return new AgentRunDefinition(question, v, maxSteps, maxAttempts); }
    public AgentRunDefinition withMaxSteps(int v) { return new AgentRunDefinition(question, pathScope, v, maxAttempts); }
    public AgentRunDefinition withMaxAttempts(int v) { return new AgentRunDefinition(question, pathScope, maxSteps, v); }
}
