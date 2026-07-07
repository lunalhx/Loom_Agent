package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.entity.AgentStep;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mutable runtime state: step counter, node, checkpoint, history, progress, and termination.
 *
 * <p>Termination is managed through behavior methods rather than individual field setters.
 */
public final class AgentRuntimeState {

    private int step;
    private int parseErrors;
    private int modelCallRetryCount;
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
    private boolean repeatedFailureReplanAttempted;
    private int replanAttemptsForFailure;
    private int noProgressRounds;
    private boolean codeReadObserved;
    private int lastWriteStep;
    private int lastTestStep;
    private Boolean lastTestPassed;
    private boolean changedSincePassingTest;
    private int verificationContinuationCount;
    private Set<String> touchedFiles = new LinkedHashSet<>();
    private Set<String> readFiles = new LinkedHashSet<>();
    private Integer lastTestExitCode;

    // -- getters --

    public int step() { return step; }
    public int parseErrors() { return parseErrors; }
    public int modelCallRetryCount() { return modelCallRetryCount; }
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
    public boolean repeatedFailureReplanAttempted() { return repeatedFailureReplanAttempted; }
    public int replanAttemptsForFailure() { return replanAttemptsForFailure; }
    public int noProgressRounds() { return noProgressRounds; }
    public boolean codeReadObserved() { return codeReadObserved; }
    public int lastWriteStep() { return lastWriteStep; }
    public int lastTestStep() { return lastTestStep; }
    public Boolean lastTestPassed() { return lastTestPassed; }
    public boolean changedSincePassingTest() { return changedSincePassingTest; }
    public int verificationContinuationCount() { return verificationContinuationCount; }
    public Set<String> touchedFiles() { return touchedFiles; }
    public Set<String> readFiles() { return readFiles; }
    public Integer lastTestExitCode() { return lastTestExitCode; }

    // -- package-private mutators for AgentContext delegation --

    public void setStep(int v) { this.step = v; }
    public void setParseErrors(int v) { this.parseErrors = v; }
    public void setModelCallRetryCount(int v) { this.modelCallRetryCount = v; }
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
    public void setRepeatedFailureReplanAttempted(boolean v) { this.repeatedFailureReplanAttempted = v; }
    public void setReplanAttemptsForFailure(int v) { this.replanAttemptsForFailure = v; }
    public void setNoProgressRounds(int v) { this.noProgressRounds = v; }
    public void setCodeReadObserved(boolean v) { this.codeReadObserved = v; }
    public void setLastWriteStep(int v) { this.lastWriteStep = v; }
    public void setLastTestStep(int v) { this.lastTestStep = v; }
    public void setLastTestPassed(Boolean v) { this.lastTestPassed = v; }
    public void setChangedSincePassingTest(boolean v) { this.changedSincePassingTest = v; }
    public void setVerificationContinuationCount(int v) { this.verificationContinuationCount = v; }
    public void setTouchedFiles(Set<String> v) {
        this.touchedFiles = v == null ? new LinkedHashSet<>() : new LinkedHashSet<>(v);
    }
    public void setReadFiles(Set<String> v) {
        this.readFiles = v == null ? new LinkedHashSet<>() : new LinkedHashSet<>(v);
    }
    public void setLastTestExitCode(Integer v) { this.lastTestExitCode = v; }

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
