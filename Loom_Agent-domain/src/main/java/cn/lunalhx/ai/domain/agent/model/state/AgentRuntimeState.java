package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.entity.AgentStep;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable runtime state: step counter, node, checkpoint, history, progress, and termination.
 *
 * <p>Termination is managed through behavior methods rather than individual field setters.
 */
public final class AgentRuntimeState {

    private int step;
    private int parseErrors;
    private Instant startedAt;
    private List<AgentStep> history = new ArrayList<>();
    private String currentNode;
    private Long checkpointVersion;
    private String finalAnswer;
    private AgentStopReason stopReason;
    private String errorCode;
    private String errorMessage;
    private int segmentIndex;
    private int segmentStartStep;
    private int stopHookContinuationCount;
    private String lastActionFingerprint;
    private int sameActionRepeats;
    private String lastFailureFingerprint;
    private int sameFailureRepeats;
    private int noProgressRounds;

    // -- getters --

    public int step() { return step; }
    public int parseErrors() { return parseErrors; }
    public Instant startedAt() { return startedAt; }
    public List<AgentStep> history() { return history; }
    public String currentNode() { return currentNode; }
    public Long checkpointVersion() { return checkpointVersion; }
    public String finalAnswer() { return finalAnswer; }
    public AgentStopReason stopReason() { return stopReason; }
    public String errorCode() { return errorCode; }
    public String errorMessage() { return errorMessage; }
    public int segmentIndex() { return segmentIndex; }
    public int segmentStartStep() { return segmentStartStep; }
    public int stopHookContinuationCount() { return stopHookContinuationCount; }
    public String lastActionFingerprint() { return lastActionFingerprint; }
    public int sameActionRepeats() { return sameActionRepeats; }
    public String lastFailureFingerprint() { return lastFailureFingerprint; }
    public int sameFailureRepeats() { return sameFailureRepeats; }
    public int noProgressRounds() { return noProgressRounds; }

    // -- package-private mutators for AgentContext delegation --

    public void setStep(int v) { this.step = v; }
    public void setParseErrors(int v) { this.parseErrors = v; }
    public void setStartedAt(Instant v) { this.startedAt = v; }
    public void setHistory(List<AgentStep> v) { this.history = v; }
    public void setCurrentNode(String v) { this.currentNode = v; }
    public void setCheckpointVersion(Long v) { this.checkpointVersion = v; }
    public void setFinalAnswer(String v) { this.finalAnswer = v; }
    public void setStopReason(AgentStopReason v) { this.stopReason = v; }
    public void setErrorCode(String v) { this.errorCode = v; }
    public void setErrorMessage(String v) { this.errorMessage = v; }
    public void setSegmentIndex(int v) { this.segmentIndex = v; }
    public void setSegmentStartStep(int v) { this.segmentStartStep = v; }
    public void setStopHookContinuationCount(int v) { this.stopHookContinuationCount = v; }
    public void setLastActionFingerprint(String v) { this.lastActionFingerprint = v; }
    public void setSameActionRepeats(int v) { this.sameActionRepeats = v; }
    public void setLastFailureFingerprint(String v) { this.lastFailureFingerprint = v; }
    public void setSameFailureRepeats(int v) { this.sameFailureRepeats = v; }
    public void setNoProgressRounds(int v) { this.noProgressRounds = v; }

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

    public void advanceStep() {
        this.step++;
    }

    public void advanceSegment() {
        this.segmentIndex++;
        this.segmentStartStep = this.step;
    }
}
