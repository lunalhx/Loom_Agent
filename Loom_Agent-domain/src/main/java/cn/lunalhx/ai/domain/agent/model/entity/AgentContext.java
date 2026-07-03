package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.state.AgentActionState;
import cn.lunalhx.ai.domain.agent.model.state.AgentApprovalState;
import cn.lunalhx.ai.domain.agent.model.state.AgentBudgetState;
import cn.lunalhx.ai.domain.agent.model.state.AgentEnvironmentState;
import cn.lunalhx.ai.domain.agent.model.state.AgentIdentity;
import cn.lunalhx.ai.domain.agent.model.state.AgentPromptState;
import cn.lunalhx.ai.domain.agent.model.state.AgentRecoveryState;
import cn.lunalhx.ai.domain.agent.model.state.AgentRunDefinition;
import cn.lunalhx.ai.domain.agent.model.state.AgentRuntimeState;
import cn.lunalhx.ai.domain.agent.model.state.AgentSkillState;
import cn.lunalhx.ai.domain.agent.model.state.AgentTraceState;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.BudgetState;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Per-run Agent state, owning partitioned sub-states and a thread-ownership guard.
 *
 * <p>Concurrency contract:
 * <ul>
 *   <li>A single context instance MUST only be mutated serially by one Agent Loop.</li>
 *   <li>Parent and child Agents MUST NOT share the same context instance.</li>
 *   <li>Cross-Agent shared budget is managed exclusively by {@code BudgetGuard}.</li>
 *   <li>Async consumers and persistence logic MUST only use immutable events or snapshots.</li>
 * </ul>
 */
public class AgentContext {

    private AgentIdentity identity;
    private AgentRunDefinition runDefinition;
    private AgentEnvironmentState environment;
    private AgentRuntimeState runtime;
    private AgentPromptState prompt;
    private AgentActionState action;
    private AgentApprovalState approval;
    private AgentBudgetState budget;
    private AgentRecoveryState recovery;
    private AgentSkillState skill;
    private AgentTraceState trace;

    public AgentContext() {
        this.identity = new AgentIdentity(null, null, null, null, null, null, 0, 0);
        this.runDefinition = new AgentRunDefinition(null, null, 0, 1, 0);
        this.environment = new AgentEnvironmentState();
        this.runtime = new AgentRuntimeState();
        this.prompt = new AgentPromptState();
        this.action = new AgentActionState();
        this.approval = new AgentApprovalState();
        this.budget = new AgentBudgetState();
        this.recovery = new AgentRecoveryState();
        this.skill = new AgentSkillState();
        this.trace = new AgentTraceState();
    }

    // ==================== component accessors ====================

    public AgentIdentity identity() { return identity; }
    public AgentRunDefinition runDefinition() { return runDefinition; }
    public AgentEnvironmentState environment() { return environment; }
    public AgentRuntimeState runtime() { return runtime; }
    public AgentPromptState prompt() { return prompt; }
    public AgentActionState action() { return action; }
    public AgentApprovalState approval() { return approval; }
    public AgentBudgetState budget() { return budget; }
    public AgentRecoveryState recovery() { return recovery; }
    public AgentSkillState skill() { return skill; }
    public AgentTraceState trace() { return trace; }

    // ==================== identity delegates ====================

    public String getRunId() { return identity.runId(); }
    public void setRunId(String v) { identity = identity.withRunId(v); }
    public String getParentRunId() { return identity.parentRunId(); }
    public void setParentRunId(String v) { identity = identity.withParentRunId(v); }
    public String getRootRunId() { return identity.rootRunId(); }
    public void setRootRunId(String v) { identity = identity.withRootRunId(v); }
    public String getRequestId() { return identity.requestId(); }
    public void setRequestId(String v) { identity = identity.withRequestId(v); }
    public String getConversationId() { return identity.conversationId(); }
    public void setConversationId(String v) { identity = identity.withConversationId(v); }
    public AgentRole getAgentRole() { return identity.agentRole(); }
    public void setAgentRole(AgentRole v) { identity = identity.withAgentRole(v); }
    public int getAgentDepth() { return identity.agentDepth(); }
    public void setAgentDepth(int v) { identity = identity.withAgentDepth(v); }
    public int getChildOrdinal() { return identity.childOrdinal(); }
    public void setChildOrdinal(int v) { identity = identity.withChildOrdinal(v); }

    // ==================== runDefinition delegates ====================

    public String getQuestion() { return runDefinition.question(); }
    public void setQuestion(String v) { runDefinition = runDefinition.withQuestion(v); }
    public String getPathScope() { return runDefinition.pathScope(); }
    public void setPathScope(String v) { runDefinition = runDefinition.withPathScope(v); }
    public int getMaxSteps() { return runDefinition.maxSteps(); }
    public void setMaxSteps(int v) { runDefinition = runDefinition.withMaxSteps(v); }
    public int getMaxSegments() { return runDefinition.maxSegments(); }
    public void setMaxSegments(int v) { runDefinition = runDefinition.withMaxSegments(v); }
    public int getMaxTotalSteps() { return runDefinition.maxTotalSteps(); }
    public void setMaxTotalSteps(int v) { runDefinition = runDefinition.withMaxTotalSteps(v); }

    // ==================== environment delegates ====================

    public Path getResolvedWorkspace() { return environment.resolvedWorkspace(); }
    public void setResolvedWorkspace(Path v) { environment.setResolvedWorkspace(v); }
    public WorkspaceRef getWorkspace() { return environment.workspace(); }
    public void setWorkspace(WorkspaceRef v) { environment.setWorkspace(v); }
    public String getWorkspaceDisplayName() { return environment.workspaceDisplayName(); }
    public void setWorkspaceDisplayName(String v) { environment.setWorkspaceDisplayName(v); }
    public List<ToolSpec> getToolSpecs() { return environment.toolSpecs(); }
    public void setToolSpecs(List<ToolSpec> v) { environment.setToolSpecs(v); }
    public boolean isSubAgentSpawnAllowed() { return environment.subAgentSpawnAllowed(); }
    public void setSubAgentSpawnAllowed(boolean v) { environment.setSubAgentSpawnAllowed(v); }

    // ==================== runtime delegates ====================

    public int getStep() { return runtime.step(); }
    public void setStep(int v) { runtime.setStep(v); }
    public int getParseErrors() { return runtime.parseErrors(); }
    public void setParseErrors(int v) { runtime.setParseErrors(v); }
    public Instant getStartedAt() { return runtime.startedAt(); }
    public void setStartedAt(Instant v) { runtime.setStartedAt(v); }
    public List<AgentStep> getHistory() { return runtime.history(); }
    public void setHistory(List<AgentStep> v) { runtime.setHistory(v); }
    public String getCurrentNode() { return runtime.currentNode(); }
    public void setCurrentNode(String v) { runtime.setCurrentNode(v); }
    public Long getCheckpointVersion() { return runtime.checkpointVersion(); }
    public void setCheckpointVersion(Long v) { runtime.setCheckpointVersion(v); }
    public String getFinalAnswer() { return runtime.finalAnswer(); }
    public void setFinalAnswer(String v) { runtime.setFinalAnswer(v); }
    public AgentStopReason getStopReason() { return runtime.stopReason(); }
    public void setStopReason(AgentStopReason v) { runtime.setStopReason(v); }
    public String getErrorCode() { return runtime.errorCode(); }
    public void setErrorCode(String v) { runtime.setErrorCode(v); }
    public String getErrorMessage() { return runtime.errorMessage(); }
    public void setErrorMessage(String v) { runtime.setErrorMessage(v); }
    public int getSegmentIndex() { return runtime.segmentIndex(); }
    public void setSegmentIndex(int v) { runtime.setSegmentIndex(v); }
    public int getSegmentStartStep() { return runtime.segmentStartStep(); }
    public void setSegmentStartStep(int v) { runtime.setSegmentStartStep(v); }
    public int getStopHookContinuationCount() { return runtime.stopHookContinuationCount(); }
    public void setStopHookContinuationCount(int v) { runtime.setStopHookContinuationCount(v); }
    public String getLastActionFingerprint() { return runtime.lastActionFingerprint(); }
    public void setLastActionFingerprint(String v) { runtime.setLastActionFingerprint(v); }
    public int getSameActionRepeats() { return runtime.sameActionRepeats(); }
    public void setSameActionRepeats(int v) { runtime.setSameActionRepeats(v); }
    public String getLastFailureFingerprint() { return runtime.lastFailureFingerprint(); }
    public void setLastFailureFingerprint(String v) { runtime.setLastFailureFingerprint(v); }
    public int getSameFailureRepeats() { return runtime.sameFailureRepeats(); }
    public void setSameFailureRepeats(int v) { runtime.setSameFailureRepeats(v); }
    public int getNoProgressRounds() { return runtime.noProgressRounds(); }
    public void setNoProgressRounds(int v) { runtime.setNoProgressRounds(v); }

    // ==================== prompt delegates ====================

    public DynamicText getDynamicText() { return prompt.dynamicText(); }
    public void setDynamicText(DynamicText v) { prompt.setDynamicText(v); }
    public String getCurrentPrompt() { return prompt.currentPrompt(); }
    public void setCurrentPrompt(String v) { prompt.setCurrentPrompt(v); }
    public String getPromptRenderCacheKey() { return prompt.promptRenderCacheKey(); }
    public void setPromptRenderCacheKey(String v) { prompt.setPromptRenderCacheKey(v); }
    public String getInstructionsHash() { return prompt.instructionsHash(); }
    public void setInstructionsHash(String v) { prompt.setInstructionsHash(v); }
    public String getModelOutput() { return prompt.modelOutput(); }
    public void setModelOutput(String v) { prompt.setModelOutput(v); }

    // ---- legacy prompt/memory delegates pending removal ----
    public String getCurrentSystemPrompt() { return prompt.currentSystemPrompt(); }
    public void setCurrentSystemPrompt(String v) { prompt.setCurrentSystemPrompt(v); }
    public String getCurrentUserPrompt() { return prompt.currentUserPrompt(); }
    public void setCurrentUserPrompt(String v) { prompt.setCurrentUserPrompt(v); }
    public String getMemoryContext() { return prompt.memoryContext(); }
    public void setMemoryContext(String v) { prompt.setMemoryContext(v); }
    public List<String> getSelectedMemoryIds() { return prompt.selectedMemoryIds(); }
    public void setSelectedMemoryIds(List<String> v) { prompt.setSelectedMemoryIds(v); }
    public long getSelectedMemoryVersion() { return prompt.selectedMemoryVersion(); }
    public void setSelectedMemoryVersion(long v) { prompt.setSelectedMemoryVersion(v); }

    // ---- conversation ledger delegates (C1) ----
    public ConversationLedger getConversationLedger() { return prompt.conversationLedger(); }
    public void setConversationLedger(ConversationLedger v) { prompt.setConversationLedger(v); }
    public StablePrefix getStablePrefix() { return prompt.stablePrefix(); }
    public void setStablePrefix(StablePrefix v) { prompt.setStablePrefix(v); }
    public int getGeneration() { return prompt.generation(); }
    public void setGeneration(int v) { prompt.setGeneration(v); }
    public boolean isLedgerActive() { return prompt.isLedgerActive(); }
    public void ensureLedgerActive() { prompt.ensureLedgerActive(); }
    public int incrementGeneration() { return prompt.incrementGeneration(); }

    // ==================== action delegates ====================

    public AgentDecision getDecision() { return action.decision(); }
    public void setDecision(AgentDecision v) { action.setDecision(v); }
    public ToolResult getToolResult() { return action.toolResult(); }
    public void setToolResult(ToolResult v) { action.setToolResult(v); }
    public AgentPlan getPlan() { return action.plan(); }
    public void setPlan(AgentPlan v) { action.setPlan(v); }
    public ReplanReason getReplanReason() { return action.replanReason(); }
    public void setReplanReason(ReplanReason v) { action.setReplanReason(v); }
    public String getReplanMessage() { return action.replanMessage(); }
    public void setReplanMessage(String v) { action.setReplanMessage(v); }

    // ==================== approval delegates ====================

    public boolean isUnsafeResumeRequired() { return approval.unsafeResumeRequired(); }
    public void setUnsafeResumeRequired(boolean v) { approval.setUnsafeResumeRequired(v); }
    public String getPendingApprovalId() { return approval.pendingApprovalId(); }
    public void setPendingApprovalId(String v) { approval.setPendingApprovalId(v); }
    public String getApprovedTool() { return approval.approvedTool(); }
    public void setApprovedTool(String v) { approval.setApprovedTool(v); }
    public String getApprovedPolicyFingerprint() { return approval.approvedPolicyFingerprint(); }
    public void setApprovedPolicyFingerprint(String v) { approval.setApprovedPolicyFingerprint(v); }
    public boolean isApprovalExpired() { return approval.approvalExpired(); }
    public void setApprovalExpired(boolean v) { approval.setApprovalExpired(v); }
    public String getExpiredApprovalId() { return approval.expiredApprovalId(); }
    public void setExpiredApprovalId(String v) { approval.setExpiredApprovalId(v); }

    // ==================== budget delegates ====================

    public BudgetState getBudgetState() { return budget.budgetState(); }
    public void setBudgetState(BudgetState v) { budget.setBudgetState(v); }

    // -- legacy budget field pending removal --
    public String getBudgetBlockedReason() { return budget.budgetBlockedReason(); }
    public void setBudgetBlockedReason(String v) { budget.setBudgetBlockedReason(v); }

    public long getUsedPromptTokens() { return budget.usedPromptTokens(); }
    public long getUsedCompletionTokens() { return budget.usedCompletionTokens(); }
    public long getUsedTokens() { return budget.usedTokens(); }
    public BigDecimal getEstimatedCost() { return budget.estimatedCost(); }

    public void setUsedPromptTokens(long v) {
        budget.replace(new BudgetState(v, budget.usedCompletionTokens(),
                budget.usedTokens(), budget.estimatedCost()));
    }

    public void setUsedCompletionTokens(long v) {
        budget.replace(new BudgetState(budget.usedPromptTokens(), v,
                budget.usedTokens(), budget.estimatedCost()));
    }

    public void setUsedTokens(long v) {
        budget.replace(new BudgetState(budget.usedPromptTokens(),
                budget.usedCompletionTokens(), v, budget.estimatedCost()));
    }

    public void setEstimatedCost(BigDecimal v) {
        budget.replace(new BudgetState(budget.usedPromptTokens(),
                budget.usedCompletionTokens(), budget.usedTokens(), v));
    }

    // ==================== recovery delegates ====================

    public int getReactiveCompactAttempts() { return recovery.reactiveCompactAttempts(); }
    public void setReactiveCompactAttempts(int v) { recovery.setReactiveCompactAttempts(v); }
    public String getCurrentModel() { return recovery.currentModel(); }
    public void setCurrentModel(String v) { recovery.setCurrentModel(v); }
    public String getFallbackReason() { return recovery.fallbackReason(); }
    public void setFallbackReason(String v) { recovery.setFallbackReason(v); }
    public ContextRecoveryStage getContextRecoveryStage() { return recovery.contextRecoveryStage(); }
    public void setContextRecoveryStage(ContextRecoveryStage v) { recovery.setContextRecoveryStage(v); }
    public String getRecoveryModelOverride() { return recovery.recoveryModelOverride(); }
    public void setRecoveryModelOverride(String v) { recovery.setRecoveryModelOverride(v); }
    public String getContextTranscriptArtifactId() { return recovery.contextTranscriptArtifactId(); }
    public void setContextTranscriptArtifactId(String v) { recovery.setContextTranscriptArtifactId(v); }
    public String getContextBlockedReason() { return recovery.contextBlockedReason(); }
    public void setContextBlockedReason(String v) { recovery.setContextBlockedReason(v); }

    // ==================== skill delegates ====================

    public List<String> getRequestedSkills() { return skill.requestedSkills(); }
    public void setRequestedSkills(List<String> v) { skill.setRequestedSkills(v); }
    public SkillCatalog getAvailableSkillCatalog() { return skill.availableSkillCatalog(); }
    public void setAvailableSkillCatalog(SkillCatalog v) { skill.setAvailableSkillCatalog(v); }
    public List<SkillActivation> getActivatedSkills() { return skill.activatedSkills(); }
    public void setActivatedSkills(List<SkillActivation> v) { skill.setActivatedSkills(v); }
    public String getSkillCatalogText() { return skill.skillCatalogText(); }
    public void setSkillCatalogText(String v) { skill.setSkillCatalogText(v); }
    public List<String> getApprovedSkillNames() { return skill.approvedSkillNames(); }
    public void setApprovedSkillNames(List<String> v) { skill.setApprovedSkillNames(v); }
    public List<String> getRejectedSkillNames() { return skill.rejectedSkillNames(); }
    public void setRejectedSkillNames(List<String> v) { skill.setRejectedSkillNames(v); }

    // ==================== trace delegates ====================

    public String getTraceId() { return trace.traceId(); }
    public void setTraceId(String v) { trace.setTraceId(v); }
    public String getCurrentSpanId() { return trace.currentSpanId(); }
    public void setCurrentSpanId(String v) { trace.setCurrentSpanId(v); }
    public String getParentSpanId() { return trace.parentSpanId(); }
    public void setParentSpanId(String v) { trace.setParentSpanId(v); }
    public long getTraceSequenceNo() { return trace.traceSequenceNo(); }
    public void setTraceSequenceNo(long v) { trace.setTraceSequenceNo(v); }

    // ==================== cross-cutting ====================

    public long nextTraceSequenceNo() {
        return trace.nextSequenceNo();
    }

    // ==================== cross-state operations ====================

    /** Block the run due to budget exceeded. Sets terminal state via runtime and recovery in one step. */
    public void blockForBudget(String code, String reason) {
        runtime.fail(AgentStopReason.BUDGET_EXCEEDED, code, reason);
    }

    /** Transition recovery into waiting-for-user-input state. */
    public void waitForRecoveryInput(String blockedReason, String transcriptArtifactId) {
        recovery.waitForUserInput(blockedReason, transcriptArtifactId);
    }
}
