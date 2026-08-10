package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.state.AgentActionState;
import cn.lunalhx.ai.domain.agent.model.state.AgentBudgetState;
import cn.lunalhx.ai.domain.agent.model.state.AgentEnvironmentState;
import cn.lunalhx.ai.domain.agent.model.state.AgentIdentity;
import cn.lunalhx.ai.domain.agent.model.state.AgentPromptState;
import cn.lunalhx.ai.domain.agent.model.state.AgentRecoveryState;
import cn.lunalhx.ai.domain.agent.model.state.AgentRunDefinition;
import cn.lunalhx.ai.domain.agent.model.state.AgentRuntimeState;
import cn.lunalhx.ai.domain.agent.model.state.AgentTraceState;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunConfig;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.BudgetState;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
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
    private AgentBudgetState budget;
    private AgentRecoveryState recovery;
    private AgentTraceState trace;

    public AgentContext() {
        this.identity = new AgentIdentity(null, null, null, null, null, 0);
        this.runDefinition = new AgentRunDefinition(null, null, null, null,
                0, 0, CollaborationMode.BUILD);
        this.environment = new AgentEnvironmentState();
        this.runtime = new AgentRuntimeState();
        this.prompt = new AgentPromptState();
        this.action = new AgentActionState();
        this.budget = new AgentBudgetState();
        this.recovery = new AgentRecoveryState();
        this.trace = new AgentTraceState();
    }

    // ==================== component accessors ====================

    public AgentIdentity identity() { return identity; }
    public AgentRunDefinition runDefinition() { return runDefinition; }
    public AgentEnvironmentState environment() { return environment; }
    public AgentRuntimeState runtime() { return runtime; }
    public AgentPromptState prompt() { return prompt; }
    public AgentActionState action() { return action; }
    public AgentBudgetState budget() { return budget; }
    public AgentRecoveryState recovery() { return recovery; }
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
    public int getAgentDepth() { return identity.agentDepth(); }
    public void setAgentDepth(int v) { identity = identity.withAgentDepth(v); }

    // ==================== runDefinition delegates ====================

    public String getQuestion() { return runDefinition.question(); }
    public void setQuestion(String v) { runDefinition = runDefinition.withQuestion(v); }
    public String getPathScope() { return runDefinition.pathScope(); }
    public void setPathScope(String v) { runDefinition = runDefinition.withPathScope(v); }
    public String getSessionId() { return runDefinition.sessionId(); }
    public void setSessionId(String v) { runDefinition = runDefinition.withSessionId(v); }
    public String getCheckpointId() { return runDefinition.checkpointId(); }
    public void setCheckpointId(String v) { runDefinition = runDefinition.withCheckpointId(v); }
    public int getMaxSteps() { return runDefinition.maxSteps(); }
    public void setMaxSteps(int v) { runDefinition = runDefinition.withMaxSteps(v); }
    public int getMaxAttempts() { return runDefinition.maxAttempts(); }
    public void setMaxAttempts(int v) { runDefinition = runDefinition.withMaxAttempts(v); }
    public CollaborationMode getCollaborationMode() { return runDefinition.collaborationMode(); }
    public void setCollaborationMode(CollaborationMode v) {
        runDefinition = runDefinition.withCollaborationMode(v);
    }

    // ==================== environment delegates ====================

    public Path getResolvedWorkspace() { return environment.resolvedWorkspace(); }
    public void setResolvedWorkspace(Path v) { environment.setResolvedWorkspace(v); }
    public WorkspaceRef getWorkspace() { return environment.workspace(); }
    public void setWorkspace(WorkspaceRef v) { environment.setWorkspace(v); }
    public String getWorkspaceDisplayName() { return environment.workspaceDisplayName(); }
    public void setWorkspaceDisplayName(String v) { environment.setWorkspaceDisplayName(v); }
    public List<ToolSpec> getToolSpecs() { return environment.toolSpecs(); }
    public void setToolSpecs(List<ToolSpec> v) { environment.setToolSpecs(v); }
    public List<String> getAllowedTools() { return environment.allowedTools(); }
    public void setAllowedTools(List<String> v) { environment.setAllowedTools(v); }
    public String getApprovalPolicy() { return environment.approvalPolicy(); }
    public void setApprovalPolicy(String v) { environment.setApprovalPolicy(v); }
    public AgentRunConfig getRunConfig() { return environment.runConfig(); }
    public void setRunConfig(AgentRunConfig v) { environment.setRunConfig(v); }
    public AgentRuntimeProperties runtimeProperties(AgentRuntimeProperties fallback) {
        return environment.runConfig() == null ? fallback : environment.runConfig().agent();
    }
    public ModelRuntimeProperties modelRuntimeProperties(ModelRuntimeProperties fallback) {
        return environment.runConfig() == null ? fallback : environment.runConfig().model();
    }

    // ==================== runtime delegates ====================

    public int getToolSteps() { return runtime.toolSteps(); }
    public void setToolSteps(int v) { runtime.setToolSteps(v); }
    public int getModelAttempts() { return runtime.modelAttempts(); }
    public void setModelAttempts(int v) { runtime.setModelAttempts(v); }
    public void advanceModelAttempt() { runtime.advanceModelAttempt(); }
    public void advanceToolStep(String tool) { runtime.advanceToolStep(tool); }
    public String getLastTool() { return runtime.lastTool(); }
    public void setLastTool(String v) { runtime.setLastTool(v); }
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
    public void stopRun(AgentStopReason reason) { runtime.stop(reason); }
    public void clearOutcomeForContinuation() { runtime.clearOutcomeForContinuation(); }
    // ==================== prompt delegates ====================

    public String getModelOutput() { return prompt.modelOutput(); }
    public void setModelOutput(String v) { prompt.setModelOutput(v); }
    public cn.lunalhx.ai.domain.agent.service.context.PreparedContextView getPreparedView() { return prompt.preparedView(); }
    public void setPreparedView(cn.lunalhx.ai.domain.agent.service.context.PreparedContextView v) { prompt.setPreparedView(v); }
    public String getWorkspaceSnapshot() { return prompt.workspaceSnapshot(); }
    public void setWorkspaceSnapshot(String v) { prompt.setWorkspaceSnapshot(v); }

    // ---- conversation ledger delegates (C1) ----
    public ConversationHistory getConversationHistory() { return prompt.conversationHistory(); }
    public void setConversationHistory(ConversationHistory v) { prompt.setConversationHistory(v); }
    public StablePrefix getStablePrefix() { return prompt.stablePrefix(); }
    public void setStablePrefix(StablePrefix v) { prompt.setStablePrefix(v); }
    public int getGeneration() { return prompt.generation(); }
    public void setGeneration(int v) { prompt.setGeneration(v); }
    public int getLastLedgerPlanVersion() { return prompt.lastLedgerPlanVersion(); }
    public void setLastLedgerPlanVersion(int v) { prompt.setLastLedgerPlanVersion(v); }
    public String getConfigFingerprint() { return prompt.configFingerprint(); }
    public void setConfigFingerprint(String v) { prompt.setConfigFingerprint(v); }
    public cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory getWorkingMemory() {
        return prompt.workingMemory();
    }
    public void setWorkingMemory(cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory v) {
        prompt.setWorkingMemory(v);
    }
    public cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory workingMemoryOrCreate() {
        if (prompt.workingMemory() == null) {
            prompt.setWorkingMemory(new cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory());
        }
        return prompt.workingMemory();
    }
    public boolean isLedgerActive() { return prompt.isLedgerActive(); }
    public boolean isLedgerReady() { return prompt.ledgerReady(); }
    public void setLedgerReady(boolean v) { prompt.setLedgerReady(v); }
    public String getPendingContinuation() { return prompt.pendingContinuation(); }
    public void setPendingContinuation(String v) { prompt.setPendingContinuation(v); }
    public void ensureLedgerActive() { prompt.ensureLedgerActive(); }
    public int incrementGeneration() { return prompt.incrementGeneration(); }

    // ==================== action delegates ====================

    public AgentDecision getDecision() { return action.decision(); }
    public void setDecision(AgentDecision v) { action.setDecision(v); }
    public ToolCall getToolCall() { return action.toolCall(); }
    public void setToolCall(ToolCall v) { action.setToolCall(v); }
    public ToolResult getToolResult() { return action.toolResult(); }
    public void setToolResult(ToolResult v) { action.setToolResult(v); }

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
    public boolean isFloorRetryPending() { return recovery.floorRetryPending(); }
    public void setFloorRetryPending(boolean v) { recovery.setFloorRetryPending(v); }

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
