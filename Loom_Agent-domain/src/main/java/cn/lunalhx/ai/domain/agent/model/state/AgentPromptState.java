package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.entity.DynamicText;

import java.util.List;

/**
 * Mutable prompt state: dynamic text, current prompt, render cache, model output,
 * and legacy prompt/memory fields pending removal.
 */
public final class AgentPromptState {

    private DynamicText dynamicText = new DynamicText();
    private String currentPrompt;
    private transient String promptRenderCacheKey;
    private transient String instructionsHash;
    private String modelOutput;

    // ---- legacy fields pending removal ----
    private String currentSystemPrompt;
    private String currentUserPrompt;
    private String memoryContext;
    private transient List<String> selectedMemoryIds;
    private transient long selectedMemoryVersion;

    public DynamicText dynamicText() { return dynamicText; }
    public String currentPrompt() { return currentPrompt; }
    public String promptRenderCacheKey() { return promptRenderCacheKey; }
    public String instructionsHash() { return instructionsHash; }
    public String modelOutput() { return modelOutput; }
    public String currentSystemPrompt() { return currentSystemPrompt; }
    public String currentUserPrompt() { return currentUserPrompt; }
    public String memoryContext() { return memoryContext; }
    public List<String> selectedMemoryIds() { return selectedMemoryIds; }
    public long selectedMemoryVersion() { return selectedMemoryVersion; }

    // -- package-private mutators --

    public void setDynamicText(DynamicText v) { this.dynamicText = v; }
    public void setCurrentPrompt(String v) { this.currentPrompt = v; }
    public void setPromptRenderCacheKey(String v) { this.promptRenderCacheKey = v; }
    public void setInstructionsHash(String v) { this.instructionsHash = v; }
    public void setModelOutput(String v) { this.modelOutput = v; }
    public void setCurrentSystemPrompt(String v) { this.currentSystemPrompt = v; }
    public void setCurrentUserPrompt(String v) { this.currentUserPrompt = v; }
    public void setMemoryContext(String v) { this.memoryContext = v; }
    public void setSelectedMemoryIds(List<String> v) { this.selectedMemoryIds = v; }
    public void setSelectedMemoryVersion(long v) { this.selectedMemoryVersion = v; }
}
