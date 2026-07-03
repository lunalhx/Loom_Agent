package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedger;
import cn.lunalhx.ai.domain.agent.model.entity.DynamicText;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;

import java.util.List;

/**
 * Mutable prompt state: dynamic text, current prompt, render cache, model output,
 * conversation ledger, stable prefix, generation, and legacy prompt/memory fields
 * pending removal.
 */
public final class AgentPromptState {

    private DynamicText dynamicText = new DynamicText();
    private String currentPrompt;
    private transient String promptRenderCacheKey;
    private transient String instructionsHash;
    private String modelOutput;

    // ---- conversation ledger (C1) ----
    private ConversationLedger conversationLedger;
    private StablePrefix stablePrefix;
    private int generation;
    private int lastLedgerPlanVersion;
    private String configFingerprint;

    // ---- bootstrap state (C9R, transient) ----
    private transient boolean ledgerReady;
    private transient String pendingContinuation;

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

    // ---- conversation ledger accessors ----
    public ConversationLedger conversationLedger() { return conversationLedger; }
    public StablePrefix stablePrefix() { return stablePrefix; }
    public int generation() { return generation; }
    public int lastLedgerPlanVersion() { return lastLedgerPlanVersion; }

    /** Config fingerprint set by the factory. */
    public String configFingerprint() { return configFingerprint; }

    /** Whether bootstrap has completed and the ledger is ready for model input. */
    public boolean ledgerReady() { return ledgerReady; }

    /** Pending continuation question set by createContinuation, consumed by bootstrap. */
    public String pendingContinuation() { return pendingContinuation; }

    /** Whether any ledger state is active (either flag is true). */
    public boolean isLedgerActive() {
        return conversationLedger != null;
    }

    // -- package-private mutators --

    public void setDynamicText(DynamicText v) { this.dynamicText = v; }
    public void setCurrentPrompt(String v) { this.currentPrompt = v; }
    public void setPromptRenderCacheKey(String v) { this.promptRenderCacheKey = v; }
    public void setInstructionsHash(String v) { this.instructionsHash = v; }
    public void setModelOutput(String v) { this.modelOutput = v; }

    // ---- conversation ledger mutators ----
    public void setConversationLedger(ConversationLedger v) { this.conversationLedger = v; }
    public void setStablePrefix(StablePrefix v) { this.stablePrefix = v; }
    public void setGeneration(int v) { this.generation = v; }
    public void setLastLedgerPlanVersion(int v) { this.lastLedgerPlanVersion = v; }
    public void setConfigFingerprint(String v) { this.configFingerprint = v; }
    public void setLedgerReady(boolean v) { this.ledgerReady = v; }
    public void setPendingContinuation(String v) { this.pendingContinuation = v; }

    /** Ensures ledger state is initialized. Safe to call repeatedly. */
    public void ensureLedgerActive() {
        if (this.conversationLedger == null) {
            this.conversationLedger = new ConversationLedger();
            this.generation = 0;
        }
    }

    /** Increment generation on compaction or prefix rebuild. */
    public int incrementGeneration() {
        return ++this.generation;
    }

    // ---- legacy fields pending removal ----
    public String currentSystemPrompt() { return currentSystemPrompt; }
    public String currentUserPrompt() { return currentUserPrompt; }
    public String memoryContext() { return memoryContext; }
    public List<String> selectedMemoryIds() { return selectedMemoryIds; }
    public long selectedMemoryVersion() { return selectedMemoryVersion; }

    public void setCurrentSystemPrompt(String v) { this.currentSystemPrompt = v; }
    public void setCurrentUserPrompt(String v) { this.currentUserPrompt = v; }
    public void setMemoryContext(String v) { this.memoryContext = v; }
    public void setSelectedMemoryIds(List<String> v) { this.selectedMemoryIds = v; }
    public void setSelectedMemoryVersion(long v) { this.selectedMemoryVersion = v; }
}
