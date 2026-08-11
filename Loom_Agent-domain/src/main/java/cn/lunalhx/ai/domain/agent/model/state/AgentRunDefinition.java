package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;

import java.util.Objects;

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
    private final CollaborationMode collaborationMode;
    private final String planTarget;
    private final Integer planRevision;
    private final long planStateVersion;

    public AgentRunDefinition(String question, String pathScope,
                              String sessionId, String checkpointId,
                              int maxSteps, int maxAttempts,
                              CollaborationMode collaborationMode,
                              String planTarget, Integer planRevision,
                              long planStateVersion) {
        this.question = question;
        this.pathScope = pathScope;
        this.sessionId = sessionId;
        this.checkpointId = checkpointId;
        this.maxSteps = maxSteps;
        this.maxAttempts = maxAttempts;
        this.collaborationMode = Objects.requireNonNull(collaborationMode,
                "collaborationMode must not be null");
        this.planTarget = planTarget;
        this.planRevision = planRevision;
        this.planStateVersion = planStateVersion;
    }

    public String question() { return question; }
    public String pathScope() { return pathScope; }
    public String sessionId() { return sessionId; }
    public String checkpointId() { return checkpointId; }
    public int maxSteps() { return maxSteps; }
    public int maxAttempts() { return maxAttempts; }
    public CollaborationMode collaborationMode() { return collaborationMode; }
    public String planTarget() { return planTarget; }
    public Integer planRevision() { return planRevision; }
    public long planStateVersion() { return planStateVersion; }

    public AgentRunDefinition withQuestion(String v) { return copy(v, pathScope, sessionId, checkpointId, maxSteps, maxAttempts); }
    public AgentRunDefinition withPathScope(String v) { return copy(question, v, sessionId, checkpointId, maxSteps, maxAttempts); }
    public AgentRunDefinition withSessionId(String v) { return copy(question, pathScope, v, checkpointId, maxSteps, maxAttempts); }
    public AgentRunDefinition withCheckpointId(String v) { return copy(question, pathScope, sessionId, v, maxSteps, maxAttempts); }
    public AgentRunDefinition withMaxSteps(int v) { return copy(question, pathScope, sessionId, checkpointId, v, maxAttempts); }
    public AgentRunDefinition withMaxAttempts(int v) { return copy(question, pathScope, sessionId, checkpointId, maxSteps, v); }
    public AgentRunDefinition withCollaborationMode(CollaborationMode v) {
        return new AgentRunDefinition(question, pathScope, sessionId, checkpointId,
                maxSteps, maxAttempts, v, planTarget, planRevision, planStateVersion);
    }
    public AgentRunDefinition withPlanTarget(String v) {
        return new AgentRunDefinition(question, pathScope, sessionId, checkpointId,
                maxSteps, maxAttempts, collaborationMode, v, planRevision, planStateVersion);
    }

    public AgentRunDefinition withPlanRevision(Integer v) {
        return new AgentRunDefinition(question, pathScope, sessionId, checkpointId,
                maxSteps, maxAttempts, collaborationMode, planTarget, v, planStateVersion);
    }

    public AgentRunDefinition withPlanStateVersion(long v) {
        return new AgentRunDefinition(question, pathScope, sessionId, checkpointId,
                maxSteps, maxAttempts, collaborationMode, planTarget, planRevision, v);
    }

    private AgentRunDefinition copy(String question, String pathScope, String sessionId,
                                    String checkpointId, int maxSteps, int maxAttempts) {
        return new AgentRunDefinition(question, pathScope, sessionId, checkpointId,
                maxSteps, maxAttempts, collaborationMode, planTarget, planRevision, planStateVersion);
    }
}
