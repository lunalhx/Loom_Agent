package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedger;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;

/**
 * Mutable prompt state for ledger-backed model input.
 */
public final class AgentPromptState {

    private transient String instructionsHash;
    private String modelOutput;

    // ---- conversation ledger (C1) ----
    private ConversationLedger conversationLedger;
    private StablePrefix stablePrefix;
    private int generation;
    private int lastLedgerPlanVersion;
    private String configFingerprint;

    // ---- ledger compaction (C10) ----
    private int lastCompactionGeneration = -1;
    private String ledgerBaselineArtifactId;

    // ---- bootstrap state (C9R, transient) ----
    private transient boolean ledgerReady;
    private transient String pendingContinuation;

    public String instructionsHash() { return instructionsHash; }
    public String modelOutput() { return modelOutput; }

    // ---- conversation ledger accessors ----
    public ConversationLedger conversationLedger() { return conversationLedger; }
    public StablePrefix stablePrefix() { return stablePrefix; }
    public int generation() { return generation; }
    public int lastLedgerPlanVersion() { return lastLedgerPlanVersion; }

    /** Config fingerprint set by the factory. */
    public String configFingerprint() { return configFingerprint; }

    /** C10: The generation in which the last compaction was applied; -1 if never. */
    public int lastCompactionGeneration() { return lastCompactionGeneration; }

    /** C10: Transcript artifact ID from the most recent ledger compaction. */
    public String ledgerBaselineArtifactId() { return ledgerBaselineArtifactId; }

    /** Whether bootstrap has completed and the ledger is ready for model input. */
    public boolean ledgerReady() { return ledgerReady; }

    /** Pending continuation question set by createContinuation, consumed by bootstrap. */
    public String pendingContinuation() { return pendingContinuation; }

    /** Whether any ledger state is active (either flag is true). */
    public boolean isLedgerActive() {
        return conversationLedger != null;
    }

    // -- package-private mutators --

    public void setInstructionsHash(String v) { this.instructionsHash = v; }
    public void setModelOutput(String v) { this.modelOutput = v; }

    // ---- conversation ledger mutators ----
    public void setConversationLedger(ConversationLedger v) { this.conversationLedger = v; }
    public void setStablePrefix(StablePrefix v) { this.stablePrefix = v; }
    public void setGeneration(int v) { this.generation = v; }
    public void setLastLedgerPlanVersion(int v) { this.lastLedgerPlanVersion = v; }
    public void setConfigFingerprint(String v) { this.configFingerprint = v; }
    public void setLastCompactionGeneration(int v) { this.lastCompactionGeneration = v; }
    public void setLedgerBaselineArtifactId(String v) { this.ledgerBaselineArtifactId = v; }
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

}
