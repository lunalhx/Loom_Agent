package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.valobj.ContextOverflowStage;

/**
 * Mutable model-call overflow state: fallback model, overflow stage, and mitigation artifacts.
 */
public final class AgentModelCallState {

    private int reactiveCompactAttempts;
    private String currentModel;
    private String fallbackReason;
    private ContextOverflowStage contextOverflowStage = ContextOverflowStage.NONE;
    private String recoveryModelOverride;
    private String contextTranscriptArtifactId;
    private String contextBlockedReason;
    private boolean modelErrorOverflowAttempted;
    private boolean floorRetryPending;

    // -- getters --

    public int reactiveCompactAttempts() { return reactiveCompactAttempts; }
    public String currentModel() { return currentModel; }
    public String fallbackReason() { return fallbackReason; }
    public ContextOverflowStage contextOverflowStage() { return contextOverflowStage; }
    public String recoveryModelOverride() { return recoveryModelOverride; }
    public String contextTranscriptArtifactId() { return contextTranscriptArtifactId; }
    public String contextBlockedReason() { return contextBlockedReason; }
    public boolean modelErrorOverflowAttempted() { return modelErrorOverflowAttempted; }
    public boolean floorRetryPending() { return floorRetryPending; }

    // -- package-private mutators --

    public void setReactiveCompactAttempts(int v) { this.reactiveCompactAttempts = v; }
    public void setCurrentModel(String v) { this.currentModel = v; }
    public void setFallbackReason(String v) { this.fallbackReason = v; }
    public void setContextOverflowStage(ContextOverflowStage v) { this.contextOverflowStage = v; }
    public void setRecoveryModelOverride(String v) { this.recoveryModelOverride = v; }
    public void setContextTranscriptArtifactId(String v) { this.contextTranscriptArtifactId = v; }
    public void setContextBlockedReason(String v) { this.contextBlockedReason = v; }
    public void setModelErrorOverflowAttempted(boolean v) { this.modelErrorOverflowAttempted = v; }
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
        this.contextOverflowStage = ContextOverflowStage.WAITING_USER_INPUT;
        this.contextBlockedReason = blockedReason;
        this.contextTranscriptArtifactId = transcriptArtifactId;
    }

    public void reset() {
        this.reactiveCompactAttempts = 0;
        this.contextOverflowStage = ContextOverflowStage.NONE;
        this.recoveryModelOverride = null;
        this.contextTranscriptArtifactId = null;
        this.contextBlockedReason = null;
        this.fallbackReason = null;
        this.modelErrorOverflowAttempted = false;
        this.floorRetryPending = false;
    }
}
