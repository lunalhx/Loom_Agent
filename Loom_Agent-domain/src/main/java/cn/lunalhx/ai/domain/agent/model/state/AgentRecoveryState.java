package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;

/**
 * Mutable recovery state: model fallback, context recovery stage, and recovery artifacts.
 */
public final class AgentRecoveryState {

    private int reactiveCompactAttempts;
    private String currentModel;
    private String fallbackReason;
    private ContextRecoveryStage contextRecoveryStage = ContextRecoveryStage.NONE;
    private String recoveryModelOverride;
    private String contextTranscriptArtifactId;
    private String contextBlockedReason;
    private boolean modelErrorRecoveryAttempted;
    private boolean floorRetryPending;

    // -- getters --

    public int reactiveCompactAttempts() { return reactiveCompactAttempts; }
    public String currentModel() { return currentModel; }
    public String fallbackReason() { return fallbackReason; }
    public ContextRecoveryStage contextRecoveryStage() { return contextRecoveryStage; }
    public String recoveryModelOverride() { return recoveryModelOverride; }
    public String contextTranscriptArtifactId() { return contextTranscriptArtifactId; }
    public String contextBlockedReason() { return contextBlockedReason; }
    public boolean modelErrorRecoveryAttempted() { return modelErrorRecoveryAttempted; }
    public boolean floorRetryPending() { return floorRetryPending; }

    // -- package-private mutators --

    public void setReactiveCompactAttempts(int v) { this.reactiveCompactAttempts = v; }
    public void setCurrentModel(String v) { this.currentModel = v; }
    public void setFallbackReason(String v) { this.fallbackReason = v; }
    public void setContextRecoveryStage(ContextRecoveryStage v) { this.contextRecoveryStage = v; }
    public void setRecoveryModelOverride(String v) { this.recoveryModelOverride = v; }
    public void setContextTranscriptArtifactId(String v) { this.contextTranscriptArtifactId = v; }
    public void setContextBlockedReason(String v) { this.contextBlockedReason = v; }
    public void setModelErrorRecoveryAttempted(boolean v) { this.modelErrorRecoveryAttempted = v; }
    public void setFloorRetryPending(boolean v) { this.floorRetryPending = v; }

    // -- behavior methods --

    public void reactiveCompacted() {
        this.reactiveCompactAttempts++;
    }

    public void selectFallbackModel(String model, String reason) {
        this.currentModel = model;
        this.fallbackReason = reason;
    }

    public void waitForUserInput(String blockedReason, String transcriptArtifactId) {
        this.contextRecoveryStage = ContextRecoveryStage.WAITING_USER_INPUT;
        this.contextBlockedReason = blockedReason;
        this.contextTranscriptArtifactId = transcriptArtifactId;
    }

    public void reset() {
        this.reactiveCompactAttempts = 0;
        this.contextRecoveryStage = ContextRecoveryStage.NONE;
        this.recoveryModelOverride = null;
        this.contextTranscriptArtifactId = null;
        this.contextBlockedReason = null;
        this.fallbackReason = null;
        this.modelErrorRecoveryAttempted = false;
        this.floorRetryPending = false;
    }
}
