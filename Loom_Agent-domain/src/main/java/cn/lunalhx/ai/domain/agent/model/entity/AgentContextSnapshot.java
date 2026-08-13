package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.state.AgentActionState;
import cn.lunalhx.ai.domain.agent.model.state.AgentBudgetState;
import cn.lunalhx.ai.domain.agent.model.state.AgentIdentity;
import cn.lunalhx.ai.domain.agent.model.state.AgentModelCallState;
import cn.lunalhx.ai.domain.agent.model.state.AgentRunDefinition;
import cn.lunalhx.ai.domain.agent.model.state.AgentRuntimeState;
import cn.lunalhx.ai.domain.agent.model.state.AgentTraceState;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextOverflowStage;
import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.skill.model.SkillCatalog;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;
import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfileKind;
import cn.lunalhx.ai.domain.tool.model.FrozenAuthorizationSnapshot;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Checkpoint snapshot v15 — durable state needed for recovery of an unfinished Run,
 * including frozen Skill Catalog / Active Skill bodies and a rehydratable authorization
 * snapshot. Host-absolute Skill package paths are never persisted.
 *
 * <p>v15 stores an exact Conversation History anchor rather than copying History.
 * Session Working Memory is not duplicated here; only the Run's Working Memory Overlay
 * is kept. Full Access Runs refuse restore after process restart. Only v15 snapshots
 * are recoverable; earlier shapes are rejected at restore time.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContextSnapshot {

    public static final int CURRENT_SCHEMA_VERSION = 15;

    private Integer schemaVersion;

    // -- identity (durable) --
    private String runId;
    private String sessionId;
    private String parentRunId;
    private String rootRunId;
    private String requestId;
    private String conversationId;
    private Integer agentDepth;
    private CollaborationMode runModeSnapshot;

    // -- run definition (durable) --
    private String question;
    private String pathScope;
    private Integer maxSteps;
    private Integer maxAttempts;
    private String checkpointId;
    private String planTarget;
    private Integer planRevision;
    private Long planStateVersion;
    private PlanBinding planBinding;

    // -- environment (only workspace ref; resolved path re-injected) --
    private WorkspaceRef workspace;

    // -- authorization audit (safe summary) + frozen rehydratable auth --
    private PermissionAction permissionDefaultAction;
    private String permissionSnapshotDigest;
    private List<String> permissionSourceDigests;
    private ExecutionProfileKind executionProfileKind;
    private String executionCapabilityFingerprint;
    private Boolean fullAccess;
    private List<String> authorizationGrantSummary;
    private FrozenAuthorizationSnapshot frozenAuthorization;

    // -- run-scoped skills (frozen; no host-absolute package roots) --
    private SkillCatalog skillCatalogSnapshot;
    private List<ActiveSkillSnapshot> activeSkills;

    // -- frozen tool contracts (name + input schema + effect envelope) --
    private List<ToolSpec> frozenToolContracts;

    // -- runtime (durable) --
    private Integer toolSteps;
    private Integer modelAttempts;
    private String lastTool;
    private Integer parseErrors;
    private Instant startedAt;
    private List<AgentStep> history;
    private String currentNode;
    private Long checkpointVersion;
    private String finalAnswer;
    private AgentStopReason stopReason;
    private String errorCode;
    private String errorMessage;

    // -- action (durable) --
    private AgentDecision decision;
    private ToolResult toolResult;
    private ToolExecutionMarker executionWindow;
    private List<ToolExecutionMarker> interruptedToolCalls;
    private AmbiguityReview ambiguityReview;
    private PendingInteraction pendingInteraction;

    // -- Plan Evidence (safe receipts only) --
    private List<EvidenceReceipt> evidenceReceipts;
    private boolean evidenceDrift;

    // -- budget (durable usage snapshot) --
    private Long usedPromptTokens;
    private Long usedCompletionTokens;
    private Long usedTokens;
    private BigDecimal estimatedCost;

    // -- recovery (durable) --
    private Integer reactiveCompactAttempts;
    private String currentModel;
    private String fallbackReason;
    private ContextOverflowStage contextRecoveryStage;
    private String recoveryModelOverride;
    private String contextTranscriptArtifactId;
    private String contextBlockedReason;

    // -- trace (only root identity; span IDs are transient) --
    private String traceId;
    private Long traceSequenceNo;

    // -- conversation history anchor (v15) — never a full History copy --
    private ConversationHistoryAnchor historyAnchor;
    private StablePrefix stablePrefix;
    private int generation;

    // -- config fingerprint (v3.1, C9) --
    /** Deterministic fingerprint of the tool/skills config when this snapshot was taken.
     *  Used on continuation/resume to detect config drift and trigger generation bumps. */
    private String configFingerprint;

    // -- working context memory (v7) --
    private cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory workingMemory;

    // ---- factory methods ----

    public static AgentContextSnapshot from(AgentContext context) {
        AgentIdentity id = context.identity();
        AgentRunDefinition def = context.runDefinition();
        AgentRuntimeState runtime = context.runtime();
        AgentActionState action = context.action();
        AgentBudgetState budget = context.budget();
        AgentModelCallState modelCall = context.modelCall();
        AgentTraceState trace = context.trace();

        FrozenAuthorizationSnapshot frozenAuth = null;
        if (context.getPermissionPolicySnapshot() != null && context.getExecutionProfile() != null) {
            frozenAuth = FrozenAuthorizationSnapshot.capture(
                    context.getPermissionPolicySnapshot(),
                    context.getExecutionProfile(),
                    context.getPermissionGrants(),
                    context.environment().runExecutionGrants(),
                    context.getApprovalPolicy(),
                    frozenAllowedTools(context));
        }

        return AgentContextSnapshot.builder()
                .schemaVersion(CURRENT_SCHEMA_VERSION)
                // identity
                .runId(id.runId())
                .sessionId(context.getSessionId())
                .parentRunId(id.parentRunId())
                .rootRunId(id.rootRunId())
                .requestId(id.requestId())
                .conversationId(id.conversationId())
                .agentDepth(id.agentDepth())
                .runModeSnapshot(def.collaborationMode())
                // run definition
                .question(def.question())
                .pathScope(def.pathScope())
                .maxSteps(def.maxSteps())
                .maxAttempts(def.maxAttempts())
                .checkpointId(context.getCheckpointId())
                .planTarget(def.planTarget())
                .planRevision(def.planRevision())
                .planStateVersion(def.planStateVersion())
                .planBinding(def.planBinding())
                // environment
                .workspace(context.environment().workspace())
                // authorization audit
                .permissionDefaultAction(context.getPermissionPolicySnapshot() == null ? null
                        : context.getPermissionPolicySnapshot().defaultAction())
                .permissionSnapshotDigest(context.getPermissionPolicySnapshot() == null ? null
                        : context.getPermissionPolicySnapshot().snapshotDigest())
                .permissionSourceDigests(context.getPermissionPolicySnapshot() == null ? List.of()
                        : context.getPermissionPolicySnapshot().sourceDigests())
                .executionProfileKind(profileKind(context.getExecutionProfile()))
                .executionCapabilityFingerprint(profileFingerprint(context.getExecutionProfile()))
                .fullAccess(context.getExecutionProfile() != null
                        && context.getExecutionProfile().kind() == ExecutionProfileKind.DANGER_FULL_ACCESS)
                .authorizationGrantSummary(grantSummary(context))
                .frozenAuthorization(frozenAuth)
                // skills
                .skillCatalogSnapshot(context.getSkillCatalogSnapshot())
                .activeSkills(context.getActiveSkills() == null ? List.of()
                        : List.copyOf(context.getActiveSkills()))
                .frozenToolContracts(context.getToolSpecs() == null ? List.of()
                        : List.copyOf(context.getToolSpecs()))
                // runtime
                .toolSteps(runtime.toolSteps())
                .modelAttempts(runtime.modelAttempts())
                .lastTool(runtime.lastTool())
                .parseErrors(runtime.parseErrors())
                .startedAt(runtime.startedAt())
                .history(runtime.history() == null ? null : new ArrayList<>(runtime.history()))
                .currentNode(runtime.currentNode())
                .checkpointVersion(runtime.checkpointVersion())
                .finalAnswer(runtime.finalAnswer())
                .stopReason(runtime.stopReason())
                .errorCode(runtime.errorCode())
                .errorMessage(runtime.errorMessage())
                // action
                .decision(action.decision())
                .toolResult(action.toolResult())
                .executionWindow(action.executionWindow())
                .interruptedToolCalls(action.interruptedToolCalls() == null ? List.of()
                        : List.copyOf(action.interruptedToolCalls()))
                .ambiguityReview(action.ambiguityReview())
                .pendingInteraction(action.pendingInteraction())
                .evidenceReceipts(context.getEvidenceReceipts())
                .evidenceDrift(context.isEvidenceDrift())
                // budget
                .usedPromptTokens(budget.usedPromptTokens())
                .usedCompletionTokens(budget.usedCompletionTokens())
                .usedTokens(budget.usedTokens())
                .estimatedCost(budget.estimatedCost())
                // recovery
                .reactiveCompactAttempts(modelCall.reactiveCompactAttempts())
                .currentModel(modelCall.currentModel())
                .fallbackReason(modelCall.fallbackReason())
                .contextRecoveryStage(modelCall.contextOverflowStage())
                .recoveryModelOverride(modelCall.recoveryModelOverride())
                .contextTranscriptArtifactId(modelCall.contextTranscriptArtifactId())
                .contextBlockedReason(modelCall.contextBlockedReason())
                // trace
                .traceId(trace.traceId())
                .traceSequenceNo(trace.traceSequenceNo())
                // conversation history anchor (v15)
                .historyAnchor(ConversationHistoryAnchor.from(context.prompt().conversationHistory()))
                .stablePrefix(context.prompt().stablePrefix())
                .generation(context.prompt().generation())
                // config fingerprint (C9)
                .configFingerprint(context.prompt().configFingerprint())
                // working memory overlay (not Session Working Memory copy)
                .workingMemory(context.getWorkingMemory())
                .build();
    }

    /**
     * Freeze the effective catalog rather than retaining an unrestricted
     * allowlist sentinel. A resumed Run must not acquire tools registered
     * after its checkpoint.
     */
    private static List<String> frozenAllowedTools(AgentContext context) {
        if (context.getAllowedTools() != null) {
            return List.copyOf(context.getAllowedTools());
        }
        ArrayList<String> names = new ArrayList<>();
        if (context.getToolSpecs() != null) {
            context.getToolSpecs().stream()
                    .map(spec -> spec == null ? null : spec.getName())
                    .filter(Objects::nonNull)
                    .filter(name -> !name.isBlank())
                    .forEach(names::add);
        }
        return names.stream().distinct().sorted().toList();
    }

    public AgentContext restore() {
        ensureCurrentShape();
        if (Boolean.TRUE.equals(fullAccess)
                || (frozenAuthorization != null
                && frozenAuthorization.profileKind() == ExecutionProfileKind.DANGER_FULL_ACCESS)
                || executionProfileKind == ExecutionProfileKind.DANGER_FULL_ACCESS) {
            throw new IllegalArgumentException(
                    "Full Access runs are not recoverable after process restart");
        }
        AgentContext context = new AgentContext();

        // identity
        context.setRunId(runId);
        context.setSessionId(sessionId);
        context.setParentRunId(parentRunId);
        context.setRootRunId(rootRunId);
        context.setRequestId(requestId);
        context.setConversationId(conversationId);
        context.setAgentDepth(agentDepth == null ? 0 : agentDepth);
        context.setCollaborationMode(runModeSnapshot);

        // run definition
        context.setQuestion(question);
        context.setPathScope(pathScope);
        context.setMaxSteps(maxSteps == null ? 0 : maxSteps);
        context.setMaxAttempts(maxAttempts == null ? 0 : maxAttempts);
        context.setCheckpointId(checkpointId);
        context.setPlanTarget(planTarget);
        context.setPlanRevision(planRevision);
        context.setPlanStateVersion(planStateVersion == null ? 0L : planStateVersion);
        context.setPlanBinding(planBinding);

        // environment — workspace ref only; resolved path and toolSpecs re-injected by factory
        context.setWorkspace(workspace);

        // skills (package roots rebound by factory)
        context.setSkillCatalogSnapshot(skillCatalogSnapshot);
        context.setActiveSkills(activeSkills == null ? List.of() : List.copyOf(activeSkills));

        // runtime
        context.setToolSteps(toolSteps == null ? 0 : toolSteps);
        context.setModelAttempts(modelAttempts == null ? 0 : modelAttempts);
        context.setLastTool(lastTool);
        context.setParseErrors(parseErrors == null ? 0 : parseErrors);
        context.setStartedAt(startedAt);
        context.setHistory(history == null ? new ArrayList<>() : new ArrayList<>(history));
        context.setCurrentNode(currentNode);
        context.setCheckpointVersion(checkpointVersion);
        context.setFinalAnswer(finalAnswer);
        context.setStopReason(stopReason);
        context.setErrorCode(errorCode);
        context.setErrorMessage(errorMessage);

        // action
        context.setDecision(decision);
        context.setToolResult(toolResult);
        context.setExecutionWindow(executionWindow);
        context.setInterruptedToolCalls(interruptedToolCalls == null ? List.of()
                : List.copyOf(interruptedToolCalls));
        context.setAmbiguityReview(ambiguityReview);
        context.setPendingInteraction(pendingInteraction);
        context.restoreEvidence(evidenceReceipts, evidenceDrift);

        // budget
        context.setUsedPromptTokens(usedPromptTokens == null ? 0L : usedPromptTokens);
        context.setUsedCompletionTokens(usedCompletionTokens == null ? 0L : usedCompletionTokens);
        context.setUsedTokens(usedTokens == null ? 0L : usedTokens);
        context.setEstimatedCost(estimatedCost == null ? BigDecimal.ZERO : estimatedCost);

        // recovery
        context.setReactiveCompactAttempts(reactiveCompactAttempts == null ? 0 : reactiveCompactAttempts);
        context.setCurrentModel(currentModel);
        context.setFallbackReason(fallbackReason);
        context.setContextOverflowStage(contextRecoveryStage == null ? ContextOverflowStage.NONE : contextRecoveryStage);
        context.setRecoveryModelOverride(recoveryModelOverride);
        context.setContextTranscriptArtifactId(contextTranscriptArtifactId);
        context.setContextBlockedReason(contextBlockedReason);

        // trace
        context.setTraceId(traceId);
        context.setTraceSequenceNo(traceSequenceNo == null ? 0L : traceSequenceNo);

        // conversation history is loaded from the Conversation History store
        // using historyAnchor — never restored from a checkpoint copy
        context.setStablePrefix(stablePrefix);
        context.setGeneration(generation);
        context.setConfigFingerprint(configFingerprint);
        context.setWorkingMemory(workingMemory);

        return context;
    }

    /** Reject obsolete persisted shapes instead of silently migrating them. */
    public void ensureCurrentShape() {
        if (schemaVersion == null || schemaVersion != CURRENT_SCHEMA_VERSION
                || runModeSnapshot == null) {
            throw new IllegalArgumentException(
                    "checkpoint snapshot uses an incompatible schema; "
                            + "no automatic migration is performed");
        }
        if (historyAnchor == null) {
            throw new IllegalArgumentException(
                    "checkpoint snapshot is missing Conversation History anchor; "
                            + "no automatic migration is performed");
        }
    }

    private static ExecutionProfileKind profileKind(ExecutionProfile profile) {
        return profile == null ? null : profile.kind();
    }

    private static String profileFingerprint(ExecutionProfile profile) {
        if (profile == null) return null;
        String descriptor = profile.kind() + "|" + profile.workspaceAccess() + "|"
                + profile.networkAllowed() + "|" + profile.hostPrivateVisible() + "|"
                + profile.externalGrants().stream().map(grant -> grant.access().name()).sorted().toList();
        return org.apache.commons.codec.digest.DigestUtils.sha256Hex(descriptor);
    }

    private static List<String> grantSummary(AgentContext context) {
        List<String> summary = new ArrayList<>();
        context.getPermissionGrants().forEach(grant -> summary.add("permission:" + grant.lifetime()));
        context.getExecutionGrants().forEach(grant -> summary.add("execution:" + grant.access()
                + ":" + grant.lifetime()));
        return List.copyOf(summary);
    }
}
