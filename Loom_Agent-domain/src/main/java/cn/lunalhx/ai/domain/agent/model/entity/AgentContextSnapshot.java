package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.state.AgentActionState;
import cn.lunalhx.ai.domain.agent.model.state.AgentApprovalState;
import cn.lunalhx.ai.domain.agent.model.state.AgentBudgetState;
import cn.lunalhx.ai.domain.agent.model.state.AgentIdentity;
import cn.lunalhx.ai.domain.agent.model.state.AgentRecoveryState;
import cn.lunalhx.ai.domain.agent.model.state.AgentRunDefinition;
import cn.lunalhx.ai.domain.agent.model.state.AgentRuntimeState;
import cn.lunalhx.ai.domain.agent.model.state.AgentTraceState;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalGrant;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Checkpoint snapshot v7 — only durable state needed for recovery.
 *
 * <p>Excluded from persistence: modelOutput, current span,
 * toolSpecs, skill catalog, resolved workspace path, display name, and deleted legacy fields.
 * These are re-injected at restore time by {@code AgentContextFactory} from current configuration.
 *
 * <p>v7 removes all legacy compression state (compaction depth/generation, durable summary,
 * Transcript artifacts) and adds working context memory. Only v7 snapshots are recoverable;
 * v6 and earlier are rejected at restore time.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContextSnapshot {

    private int schemaVersion = 7;

    // -- identity (durable) --
    private String runId;
    private String parentRunId;
    private String rootRunId;
    private String requestId;
    private String conversationId;
    private Integer agentDepth;

    // -- run definition (durable) --
    private String question;
    private String pathScope;
    private Integer maxSteps;
    private Integer maxSegments;
    private Integer maxTotalSteps;

    // -- environment (only workspace ref; resolved path re-injected) --
    private WorkspaceRef workspace;

    // -- runtime (durable) --
    private Integer step;
    private Integer parseErrors;
    private Instant startedAt;
    private List<AgentStep> history;
    // Legacy field — no longer written; kept for Jackson backward compat on old snapshots
    private List<DynamicTextEntry> dynamicTextEntries;
    private String currentNode;
    private Long checkpointVersion;
    private String finalAnswer;
    private AgentStopReason stopReason;
    private String errorCode;
    private String errorMessage;
    private Integer segmentIndex;
    private Integer segmentStartStep;
    private Integer stopHookContinuationCount;
    private Boolean codeReadObserved;
    private Integer lastWriteStep;
    private Integer lastTestStep;
    private Boolean lastTestPassed;
    private Boolean changedSincePassingTest;
    private Integer verificationContinuationCount;
    private List<String> touchedFiles;
    private List<String> readFiles;
    private Integer lastTestExitCode;

    // -- action (durable) --
    private AgentDecision decision;
    private ToolResult toolResult;

    // -- approval (durable) --
    private Boolean unsafeResumeRequired;
    private String pendingApprovalId;
    private String approvedTool;
    private String approvedPolicyFingerprint;
    private Boolean approvalExpired;
    private String expiredApprovalId;
    private List<ApprovalGrant> approvalGrants;

    // -- budget (durable usage snapshot) --
    private Long usedPromptTokens;
    private Long usedCompletionTokens;
    private Long usedTokens;
    private BigDecimal estimatedCost;

    // -- recovery (durable) --
    private Integer reactiveCompactAttempts;
    private String currentModel;
    private String fallbackReason;
    private ContextRecoveryStage contextRecoveryStage;
    private String recoveryModelOverride;
    private String contextTranscriptArtifactId;
    private String contextBlockedReason;

    // -- trace (only root identity; span IDs are transient) --
    private String traceId;
    private Long traceSequenceNo;

    // -- conversation history (v3) --
    private List<ConversationHistoryEntry> ledgerEntries;
    private long ledgerNextSequence;
    private StablePrefix stablePrefix;
    private int generation;

    // -- config fingerprint (v3.1, C9) --
    /** Deterministic fingerprint of the tool/skills config when this snapshot was taken.
     *  Used on continuation/resume to detect config drift and trigger generation bumps. */
    private String configFingerprint;

    // -- working context memory (v7) --
    private cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory workingMemory;

    // ---- factory methods ----

    /** Defensive copy of ledger entries for snapshot isolation. */
    private static List<ConversationHistoryEntry> captureLedgerEntries(AgentContext context) {
        ConversationHistory ledger = context.prompt().conversationHistory();
        if (ledger == null || ledger.isEmpty()) {
            return null;
        }
        return new ArrayList<>(ledger.entries());
    }

    public static AgentContextSnapshot from(AgentContext context) {
        AgentIdentity id = context.identity();
        AgentRunDefinition def = context.runDefinition();
        AgentRuntimeState runtime = context.runtime();
        AgentActionState action = context.action();
        AgentApprovalState approval = context.approval();
        AgentBudgetState budget = context.budget();
        AgentRecoveryState recovery = context.recovery();
        AgentTraceState trace = context.trace();

        return AgentContextSnapshot.builder()
                .schemaVersion(8)
                // identity
                .runId(id.runId())
                .parentRunId(id.parentRunId())
                .rootRunId(id.rootRunId())
                .requestId(id.requestId())
                .conversationId(id.conversationId())
                .agentDepth(id.agentDepth())
                // run definition
                .question(def.question())
                .pathScope(def.pathScope())
                .maxSteps(def.maxSteps())
                .maxSegments(def.maxSegments())
                .maxTotalSteps(def.maxTotalSteps())
                // environment
                .workspace(context.environment().workspace())
                // runtime
                .step(runtime.step())
                .parseErrors(runtime.parseErrors())
                .startedAt(runtime.startedAt())
                .history(runtime.history() == null ? null : new ArrayList<>(runtime.history()))
                .currentNode(runtime.currentNode())
                .checkpointVersion(runtime.checkpointVersion())
                .finalAnswer(runtime.finalAnswer())
                .stopReason(runtime.stopReason())
                .errorCode(runtime.errorCode())
                .errorMessage(runtime.errorMessage())
                .segmentIndex(runtime.segmentIndex())
                .segmentStartStep(runtime.segmentStartStep())
                .stopHookContinuationCount(runtime.stopHookContinuationCount())
                .codeReadObserved(runtime.codeReadObserved())
                .lastWriteStep(runtime.lastWriteStep())
                .lastTestStep(runtime.lastTestStep())
                .lastTestPassed(runtime.lastTestPassed())
                .changedSincePassingTest(runtime.changedSincePassingTest())
                .verificationContinuationCount(runtime.verificationContinuationCount())
                .touchedFiles(new ArrayList<>(runtime.touchedFiles()))
                .readFiles(new ArrayList<>(runtime.readFiles()))
                .lastTestExitCode(runtime.lastTestExitCode())
                // action
                .decision(action.decision())
                .toolResult(action.toolResult())
                // approval
                .unsafeResumeRequired(approval.unsafeResumeRequired())
                .pendingApprovalId(approval.pendingApprovalId())
                .approvedTool(approval.approvedTool())
                .approvedPolicyFingerprint(approval.approvedPolicyFingerprint())
                .approvalExpired(approval.approvalExpired())
                .expiredApprovalId(approval.expiredApprovalId())
                .approvalGrants(approval.approvalGrants() == null ? null : new ArrayList<>(approval.approvalGrants()))
                // budget
                .usedPromptTokens(budget.usedPromptTokens())
                .usedCompletionTokens(budget.usedCompletionTokens())
                .usedTokens(budget.usedTokens())
                .estimatedCost(budget.estimatedCost())
                // recovery
                .reactiveCompactAttempts(recovery.reactiveCompactAttempts())
                .currentModel(recovery.currentModel())
                .fallbackReason(recovery.fallbackReason())
                .contextRecoveryStage(recovery.contextRecoveryStage())
                .recoveryModelOverride(recovery.recoveryModelOverride())
                .contextTranscriptArtifactId(recovery.contextTranscriptArtifactId())
                .contextBlockedReason(recovery.contextBlockedReason())
                // trace
                .traceId(trace.traceId())
                .traceSequenceNo(trace.traceSequenceNo())
                // conversation ledger (v3)
                .ledgerEntries(captureLedgerEntries(context))
                .ledgerNextSequence(context.prompt().conversationHistory() != null
                        ? context.prompt().conversationHistory().nextSequence() : 0)
                .stablePrefix(context.prompt().stablePrefix())
                .generation(context.prompt().generation())
                // config fingerprint (C9)
                .configFingerprint(context.prompt().configFingerprint())
                // working memory (v7)
                .workingMemory(context.getWorkingMemory())
                .build();
    }

    public AgentContext restore() {
        AgentContext context = new AgentContext();

        // identity
        context.setRunId(runId);
        context.setParentRunId(parentRunId);
        context.setRootRunId(rootRunId);
        context.setRequestId(requestId);
        context.setConversationId(conversationId);
        context.setAgentDepth(agentDepth == null ? 0 : agentDepth);

        // run definition
        context.setQuestion(question);
        context.setPathScope(pathScope);
        context.setMaxSteps(maxSteps == null ? 0 : maxSteps);
        context.setMaxSegments(maxSegments == null ? 1 : maxSegments);
        context.setMaxTotalSteps(maxTotalSteps == null ? context.getMaxSteps() : maxTotalSteps);

        // environment — workspace ref only; resolved path and toolSpecs re-injected by factory
        context.setWorkspace(workspace);

        // runtime
        context.setStep(step == null ? 0 : step);
        context.setParseErrors(parseErrors == null ? 0 : parseErrors);
        context.setStartedAt(startedAt);
        context.setHistory(history == null ? new ArrayList<>() : new ArrayList<>(history));
        context.setCurrentNode(currentNode);
        context.setCheckpointVersion(checkpointVersion);
        context.setFinalAnswer(finalAnswer);
        context.setStopReason(stopReason);
        context.setErrorCode(errorCode);
        context.setErrorMessage(errorMessage);
        context.setSegmentIndex(segmentIndex == null ? 0 : segmentIndex);
        context.setSegmentStartStep(segmentStartStep == null ? 0 : segmentStartStep);
        context.setStopHookContinuationCount(stopHookContinuationCount == null ? 0 : stopHookContinuationCount);
        context.setCodeReadObserved(Boolean.TRUE.equals(codeReadObserved));
        context.setLastWriteStep(lastWriteStep == null ? 0 : lastWriteStep);
        context.setLastTestStep(lastTestStep == null ? 0 : lastTestStep);
        context.setLastTestPassed(lastTestPassed);
        context.setChangedSincePassingTest(
                Boolean.TRUE.equals(changedSincePassingTest));
        context.setVerificationContinuationCount(
                verificationContinuationCount == null ? 0 : verificationContinuationCount);
        context.setTouchedFiles(touchedFiles == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(touchedFiles));
        context.setReadFiles(readFiles == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(readFiles));
        context.setLastTestExitCode(lastTestExitCode);

        // action
        context.setDecision(decision);
        context.setToolResult(toolResult);

        // approval
        context.setUnsafeResumeRequired(Boolean.TRUE.equals(unsafeResumeRequired));
        context.setPendingApprovalId(pendingApprovalId);
        context.setApprovedTool(approvedTool);
        context.setApprovedPolicyFingerprint(approvedPolicyFingerprint);
        context.setApprovalExpired(Boolean.TRUE.equals(approvalExpired));
        context.setExpiredApprovalId(expiredApprovalId);
        context.setApprovalGrants(approvalGrants == null ? null : new ArrayList<>(approvalGrants));

        // budget
        context.setUsedPromptTokens(usedPromptTokens == null ? 0L : usedPromptTokens);
        context.setUsedCompletionTokens(usedCompletionTokens == null ? 0L : usedCompletionTokens);
        context.setUsedTokens(usedTokens == null ? 0L : usedTokens);
        context.setEstimatedCost(estimatedCost == null ? BigDecimal.ZERO : estimatedCost);

        // recovery
        context.setReactiveCompactAttempts(reactiveCompactAttempts == null ? 0 : reactiveCompactAttempts);
        context.setCurrentModel(currentModel);
        context.setFallbackReason(fallbackReason);
        context.setContextRecoveryStage(contextRecoveryStage == null ? ContextRecoveryStage.NONE : contextRecoveryStage);
        context.setRecoveryModelOverride(recoveryModelOverride);
        context.setContextTranscriptArtifactId(contextTranscriptArtifactId);
        context.setContextBlockedReason(contextBlockedReason);

        // trace
        context.setTraceId(traceId);
        context.setTraceSequenceNo(traceSequenceNo == null ? 0L : traceSequenceNo);

        // conversation ledger (v3) — defensive reconstruct
        if (ledgerEntries != null && !ledgerEntries.isEmpty()) {
            context.setConversationHistory(ConversationHistory.fromPersisted(
                    new ArrayList<>(ledgerEntries), ledgerNextSequence));
        }
        // stablePrefix is immutable — safe to share
        context.setStablePrefix(stablePrefix);
        context.setGeneration(generation);
        context.setConfigFingerprint(configFingerprint);
        context.setWorkingMemory(workingMemory);

        return context;
    }
}
