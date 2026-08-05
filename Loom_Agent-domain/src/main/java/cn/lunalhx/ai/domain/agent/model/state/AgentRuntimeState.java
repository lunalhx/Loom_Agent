package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.entity.AgentStep;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable runtime state: tool/model counters, node, checkpoint, history, and termination.
 *
 * <p>Termination is managed through behavior methods rather than individual field setters.
 */
public final class AgentRuntimeState {

    private int toolSteps;
    private int modelAttempts;
    private String lastTool;
    private int parseErrors;
    private Instant startedAt;
    private List<AgentStep> history = new ArrayList<>();
    private String currentNode;
    private Long checkpointVersion;
    private String finalAnswer;
    private AgentStopReason stopReason;
    private String errorCode;
    private String errorMessage;

    // -- getters --

    public int toolSteps() { return toolSteps; }
    public int modelAttempts() { return modelAttempts; }
    public String lastTool() { return lastTool; }
    public int parseErrors() { return parseErrors; }
    public Instant startedAt() { return startedAt; }
    public List<AgentStep> history() { return history; }
    public String currentNode() { return currentNode; }
    public Long checkpointVersion() { return checkpointVersion; }
    public String finalAnswer() { return finalAnswer; }
    public AgentStopReason stopReason() { return stopReason; }
    public String errorCode() { return errorCode; }
    public String errorMessage() { return errorMessage; }

    // -- package-private mutators for AgentContext delegation --

    public void setToolSteps(int v) { this.toolSteps = v; }
    public void setModelAttempts(int v) { this.modelAttempts = v; }
    public void setLastTool(String v) { this.lastTool = v; }
    public void setParseErrors(int v) { this.parseErrors = v; }
    public void setStartedAt(Instant v) { this.startedAt = v; }
    public void setHistory(List<AgentStep> v) { this.history = v; }
    public void setCurrentNode(String v) { this.currentNode = v; }
    public void setCheckpointVersion(Long v) { this.checkpointVersion = v; }
    public void setFinalAnswer(String v) { this.finalAnswer = v; }
    public void setStopReason(AgentStopReason v) { this.stopReason = v; }
    public void setErrorCode(String v) { this.errorCode = v; }
    public void setErrorMessage(String v) { this.errorMessage = v; }

    // -- behavior methods (final API, usable now) --

    public void fail(AgentStopReason reason, String code, String message) {
        this.stopReason = reason;
        this.errorCode = code;
        this.errorMessage = message;
        this.finalAnswer = null;
    }

    public void complete(String answer) {
        this.finalAnswer = answer;
        this.stopReason = null;
        this.errorCode = null;
        this.errorMessage = null;
    }

    public void stop(AgentStopReason reason) {
        this.stopReason = reason;
        this.errorCode = null;
        this.errorMessage = null;
    }

    public void cancel() {
        this.stopReason = AgentStopReason.USER_CANCELLED;
        this.errorCode = null;
        this.errorMessage = null;
    }

    public void clearOutcomeForContinuation() {
        this.stopReason = null;
        this.finalAnswer = null;
        this.errorCode = null;
        this.errorMessage = null;
    }

    public void enterNode(String node) {
        this.currentNode = node;
    }

    public void advanceToolStep(String tool) {
        this.toolSteps++;
        this.lastTool = tool;
    }

    public void advanceModelAttempt() {
        this.modelAttempts++;
    }
}
