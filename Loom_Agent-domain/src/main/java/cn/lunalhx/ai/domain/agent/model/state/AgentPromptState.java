package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistory;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;

import java.util.List;

/**
 * Mutable prompt state for ledger-backed model input.
 */
public final class AgentPromptState {

    private String modelOutput;

    // ---- conversation history (C1) ----
    private ConversationHistory conversationHistory;
    private StablePrefix stablePrefix;
    private int generation;
    private int lastLedgerPlanVersion;
    private String configFingerprint;

    // ---- working context memory ----
    private WorkingContextMemory workingMemory;

    // ---- bootstrap state (C9R, transient) ----
    private transient boolean ledgerReady;
    private transient String pendingContinuation;
    private transient cn.lunalhx.ai.domain.agent.service.context.PreparedContextView preparedView;
    private transient String workspaceSnapshot;

    public String modelOutput() { return modelOutput; }

    // ---- conversation ledger accessors ----
    public ConversationHistory conversationHistory() { return conversationHistory; }
    public StablePrefix stablePrefix() { return stablePrefix; }
    public int generation() { return generation; }
    public int lastLedgerPlanVersion() { return lastLedgerPlanVersion; }

    /** Config fingerprint set by the factory. */
    public String configFingerprint() { return configFingerprint; }

    /** Working context memory (task summary, recent files, file summaries, notes). */
    public WorkingContextMemory workingMemory() { return workingMemory; }

    /** Whether bootstrap has completed and the ledger is ready for model input. */
    public boolean ledgerReady() { return ledgerReady; }

    /** Pending continuation question set by createContinuation, consumed by bootstrap. */
    public String pendingContinuation() { return pendingContinuation; }

    /** Transient per-round prepared context view, built by PromptBuildNode. */
    public cn.lunalhx.ai.domain.agent.service.context.PreparedContextView preparedView() { return preparedView; }

    /** Transient per-round dynamic workspace snapshot (status/commits/docs). */
    public String workspaceSnapshot() { return workspaceSnapshot; }

    /** Whether any ledger state is active (either flag is true). */
    public boolean isLedgerActive() {
        return conversationHistory != null;
    }

    // -- package-private mutators --

    public void setModelOutput(String v) { this.modelOutput = v; }

    // ---- conversation ledger mutators ----
    public void setConversationHistory(ConversationHistory v) { this.conversationHistory = v; }
    public void setStablePrefix(StablePrefix v) { this.stablePrefix = v; }
    public void setGeneration(int v) { this.generation = v; }
    public void setLastLedgerPlanVersion(int v) { this.lastLedgerPlanVersion = v; }
    public void setConfigFingerprint(String v) { this.configFingerprint = v; }
    public void setWorkingMemory(WorkingContextMemory v) { this.workingMemory = v; }
    public void setLedgerReady(boolean v) { this.ledgerReady = v; }
    public void setPendingContinuation(String v) { this.pendingContinuation = v; }
    public void setPreparedView(cn.lunalhx.ai.domain.agent.service.context.PreparedContextView v) { this.preparedView = v; }
    public void setWorkspaceSnapshot(String v) { this.workspaceSnapshot = v; }

    /** Ensures ledger state is initialized. Safe to call repeatedly. */
    public void ensureLedgerActive() {
        if (this.conversationHistory == null) {
            this.conversationHistory = new ConversationHistory();
            this.generation = 0;
        }
    }

    /** Increment generation on compaction or prefix rebuild. */
    public int incrementGeneration() {
        return ++this.generation;
    }

}
