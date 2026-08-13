package cn.lunalhx.ai.domain.agent.service.context;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRuntimeConfigSource;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationHistoryRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistory;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryAnchor;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryDocument;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryEntry;
import cn.lunalhx.ai.domain.agent.model.entity.RootRunSecurityScope;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentWorkspace;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.service.recovery.RecoveredToolCatalog;
import cn.lunalhx.ai.domain.agent.service.workspace.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.skill.service.SkillPackageRootBinder;
import cn.lunalhx.ai.domain.skill.service.SkillToolCatalogProjector;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.PermissionRule;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.RunAuthorizationSource;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AgentContextFactory {

    private final AgentRuntimeProperties properties;
    private final AgentWorkspaceResolver workspaceResolver;
    private final AgentRuntimeConfigSource runtimeConfigSource;
    private final ToolRegistry toolRegistry;
    private final ConversationHistoryRepository historyRepository;

    public AgentContextFactory(AgentRuntimeProperties properties,
                               AgentWorkspaceResolver workspaceResolver,
                               AgentRuntimeConfigSource runtimeConfigSource,
                               ToolRegistry toolRegistry,
                               ConversationHistoryRepository historyRepository) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.workspaceResolver = Objects.requireNonNull(workspaceResolver,
                "workspaceResolver must not be null");
        this.runtimeConfigSource = Objects.requireNonNull(runtimeConfigSource,
                "runtimeConfigSource must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.historyRepository = Objects.requireNonNull(historyRepository,
                "historyRepository must not be null");
    }

    public AgentContext create(AgentQuestion question) {
        AgentWorkspace workspace = workspaceResolver.resolve(question.getWorkspace());
        String runId = StringUtils.defaultIfBlank(question.getRunId(), UUID.randomUUID().toString());
        AgentContext context = new AgentContext();
        context.setRunConfig(runtimeConfigSource.captureRunConfig());
        applyCommonFields(context, question, workspace, runId);
        if (StringUtils.isBlank(question.getConversationId())) {
            context.setConversationId(UUID.randomUUID().toString());
        }
        restoreSeed(context, question);
        return context;
    }

    /** A new root run seeded with the session's durable history/working memory
     *  (not a node-position resume). The run keeps its own fresh identity. */
    private void restoreSeed(AgentContext context, AgentQuestion question) {
        AgentContextSnapshot previous = question.getSeedSnapshot();
        if (question.getSeedHistoryEntries() != null && !question.getSeedHistoryEntries().isEmpty()) {
            context.setConversationHistory(ConversationHistory.fromPersisted(
                    new ArrayList<>(question.getSeedHistoryEntries()),
                    question.getSeedHistoryNextSequence() == null
                            ? 0L : question.getSeedHistoryNextSequence()));
        }
        if (previous == null) {
            context.setPendingContinuation(context.getQuestion());
            return;
        }
        previous.ensureCurrentShape();
        if (previous.getWorkingMemory() != null) {
            context.setWorkingMemory(previous.getWorkingMemory());
        }
        context.setStablePrefix(previous.getStablePrefix());
        context.setGeneration(Math.max(0, previous.getGeneration()));
        context.setPendingContinuation(context.getQuestion());
    }

    public AgentContext createContinuation(AgentQuestion question, AgentContextSnapshot previous) {
        AgentWorkspace workspace = workspaceResolver.resolve(question.getWorkspace());
        String runId = StringUtils.defaultIfBlank(question.getRunId(), UUID.randomUUID().toString());
        String requestId = StringUtils.defaultIfBlank(question.getRequestId(), UUID.randomUUID().toString());

        AgentContext context = new AgentContext();
        context.setRunConfig(runtimeConfigSource.captureRunConfig());
        if (previous != null) {
            previous.ensureCurrentShape();
        }
        context.setRunId(runId);
        context.setParentRunId(question.getParentRunId());
        String rootRunId = previous != null && StringUtils.isNotBlank(previous.getRootRunId())
                ? previous.getRootRunId()
                : StringUtils.defaultIfBlank(question.getRootRunId(), runId);
        context.setRootRunId(rootRunId);
        context.setRequestId(requestId);
        context.setConversationId(question.getConversationId());
        context.setAgentDepth(question.getAgentDepth() == null ? 0 : question.getAgentDepth());
        context.setQuestion(StringUtils.trim(question.getQuestion()));
        context.setPathScope(question.getPathScope());
        context.setSessionId(question.getSessionId());
        context.setCheckpointId(question.getCheckpointId());
        context.setPlanTarget(question.getPlanTarget());
        context.setPlanRevision(question.getPlanRevision());
        context.setPlanStateVersion(question.getPlanStateVersion() == null ? 0L : question.getPlanStateVersion());
        context.setPlanBinding(question.getPlanBinding() != null
                ? question.getPlanBinding()
                : previous == null ? null : previous.getPlanBinding());
        context.setCollaborationMode(resolveMode(question, previous));
        context.setResolvedWorkspace(workspace.getRoot());
        context.setWorkspace(workspace.getWorkspace());
        context.setWorkspaceDisplayName(workspace.getDisplayName());
        AgentRuntimeProperties runProperties = context.runtimeProperties(properties);
        int maxSteps = question.getMaxSteps() == null ? runProperties.getMaxSteps() : question.getMaxSteps();
        context.setMaxSteps(maxSteps);
        context.setMaxAttempts(question.getMaxAttempts() == null
                ? Math.max(maxSteps * 3, maxSteps + 4)
                : Math.max(1, question.getMaxAttempts()));
        context.setStartedAt(Instant.now());
        context.setToolSteps(0);
        context.setModelAttempts(0);
        context.setParseErrors(0);
        context.setAllowedTools(normalizeAllowedTools(question.getAllowedTools()));
        context.setApprovalPolicy(resolveApprovalPolicy(question, runProperties));
        context.setPermissionPolicySnapshot(question.getInheritedPermissionPolicySnapshot());
        context.setSessionExecutionGrants(question.getInheritedSessionExecutionGrants());
        context.setSecurityScope(question.getInheritedSecurityScope() == null
                ? RootRunSecurityScope.create() : question.getInheritedSecurityScope());
        freezeAuthorization(context, question.isFullAccess());
        freezeEffectiveToolCatalog(context);
        applyInheritedSkills(context, question);
        context.setToolSpecs(SkillToolCatalogProjector.project(context, toolRegistry));
        context.setTraceId(StringUtils.defaultIfBlank(question.getTraceId(), context.getRootRunId()));

        if (previous != null) {
            ConversationHistory history = loadHistoryForSnapshot(previous, question.getSessionId());
            if (history != null) {
                context.setConversationHistory(history);
            }
            if (previous.getStablePrefix() != null) {
                context.setStablePrefix(previous.getStablePrefix());
                context.setGeneration(Math.max(0, previous.getGeneration()));
            }
            if (previous.getWorkingMemory() != null) {
                context.setWorkingMemory(previous.getWorkingMemory());
            }
            context.restoreEvidence(previous.getEvidenceReceipts(), previous.isEvidenceDrift());
        }

        context.setPendingContinuation(context.getQuestion());

        applyAttemptRuntime(context, question);
        return context;
    }

    /**
     * Continue an unfinished Run from its durable checkpoint. Creates a new
     * disposable security scope, rehydrates frozen mode/policy/grants/capability
     * profile and Skill state, and rebinds Skill package roots without
     * rediscovering catalog content from disk.
     */
    public AgentContext restoreFromCheckpoint(AgentQuestion question, AgentContextSnapshot checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        checkpoint.ensureCurrentShape();
        AgentWorkspace workspace = workspaceResolver.resolve(
                StringUtils.defaultIfBlank(question.getWorkspace(),
                        checkpoint.getWorkspace() == null ? null : checkpoint.getWorkspace().getLocation()));
        AgentContext context = checkpoint.restore();
        context.setRunConfig(runtimeConfigSource.captureRunConfig());
        context.setResolvedWorkspace(workspace.getRoot());
        context.setWorkspace(workspace.getWorkspace());
        context.setWorkspaceDisplayName(workspace.getDisplayName());
        String sessionId = StringUtils.defaultIfBlank(question.getSessionId(), checkpoint.getSessionId());
        if (StringUtils.isNotBlank(sessionId)) {
            context.setSessionId(sessionId);
            ConversationHistory history = loadAndValidateHistory(sessionId, checkpoint.getHistoryAnchor());
            context.setConversationHistory(history);
        }
        context.setSessionExecutionGrants(question.getInheritedSessionExecutionGrants());
        RootRunSecurityScope scope = RootRunSecurityScope.create();
        context.setSecurityScope(scope);
        rehydrateFrozenAuthorization(context, checkpoint, workspace.getRoot(), scope);
        Path userHome = Path.of(System.getProperty("user.home"));
        SkillPackageRootBinder binder = new SkillPackageRootBinder();
        context.setSkillCatalogSnapshot(binder.rebindCatalog(
                context.getSkillCatalogSnapshot(), workspace.getRoot(), userHome));
        context.setActiveSkills(binder.rebindActive(
                context.getActiveSkills(), workspace.getRoot(), userHome));
        applyRecoveredToolCatalog(context, checkpoint);
        applyAttemptRuntime(context, question);
        return context;
    }

    private void applyRecoveredToolCatalog(AgentContext context, AgentContextSnapshot checkpoint) {
        List<ToolSpec> frozen = checkpoint.getFrozenToolContracts() == null
                ? List.of() : List.copyOf(checkpoint.getFrozenToolContracts());
        context.setFrozenToolContracts(frozen);
        context.setAllowedTools(frozen.stream()
                .map(ToolSpec::getName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList());
        List<ToolSpec> recovered = RecoveredToolCatalog.keepCompatible(
                frozen, SkillToolCatalogProjector.project(context, toolRegistry));
        context.setToolSpecs(recovered);
        context.setAllowedTools(recovered.stream()
                .map(ToolSpec::getName)
                .toList());
    }

    private ConversationHistory loadHistoryForSnapshot(AgentContextSnapshot snapshot, String sessionId) {
        if (snapshot.getHistoryAnchor() == null || StringUtils.isBlank(sessionId)) {
            return null;
        }
        return loadAndValidateHistory(sessionId, snapshot.getHistoryAnchor());
    }

    private ConversationHistory loadAndValidateHistory(String sessionId, ConversationHistoryAnchor anchor) {
        ConversationHistoryDocument document = historyRepository.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "conversation history not found for session " + sessionId));
        if (document.getNextSequence() < anchor.getNextSequence()) {
            throw new IllegalArgumentException(
                    "conversation history nextSequence " + document.getNextSequence()
                            + " is behind checkpoint anchor " + anchor.getNextSequence());
        }
        if (anchor.getLastEntryId() != null) {
            long expectedSequence = anchor.getNextSequence() - 1;
            ConversationHistoryEntry lastEntry = document.getEntries() == null ? null
                    : document.getEntries().stream()
                    .filter(entry -> entry.sequence() == expectedSequence)
                    .findFirst()
                    .orElse(null);
            if (lastEntry == null || !anchor.getLastEntryId().equals(lastEntry.entryId())) {
                throw new IllegalArgumentException(
                        "conversation history anchor lastEntryId does not match durable entry");
            }
        }
        // History is authoritative when it is ahead of the checkpoint anchor.
        // The anchor only proves the checkpoint does not point past History.
        List<ConversationHistoryEntry> entries = document.getEntries() == null
                ? List.of() : List.copyOf(document.getEntries());
        return ConversationHistory.fromPersisted(new ArrayList<>(entries), document.getNextSequence());
    }

    private void rehydrateFrozenAuthorization(AgentContext context,
                                              AgentContextSnapshot checkpoint,
                                              Path workspace,
                                              RootRunSecurityScope scope) {
        cn.lunalhx.ai.domain.tool.model.FrozenAuthorizationSnapshot frozen =
                checkpoint.getFrozenAuthorization();
        if (frozen == null) {
            throw new IllegalArgumentException(
                    "checkpoint lacks frozen authorization; refusing restore");
        }
        context.setApprovalPolicy(frozen.approvalPolicy());
        context.setPermissionPolicySnapshot(frozen.toPolicy());
        context.setPermissionGrants(frozen.permissionGrants());
        context.setExecutionGrants(frozen.executionGrants());
        context.setAllowedTools(frozen.allowedTools());
        context.setExecutionProfile(frozen.toExecutionProfile(
                workspace, scope.homeRoot(), scope.temporaryRoot()));
    }

    private void applyCommonFields(AgentContext context, AgentQuestion question, AgentWorkspace workspace, String runId) {
        context.setRunId(runId);
        context.setParentRunId(question.getParentRunId());
        context.setRootRunId(StringUtils.defaultIfBlank(question.getRootRunId(), runId));
        context.setTraceId(StringUtils.defaultIfBlank(question.getTraceId(), context.getRootRunId()));
        context.setRequestId(StringUtils.defaultIfBlank(question.getRequestId(), UUID.randomUUID().toString()));
        context.setConversationId(StringUtils.defaultIfBlank(question.getConversationId(), UUID.randomUUID().toString()));
        context.setAgentDepth(question.getAgentDepth() == null ? 0 : question.getAgentDepth());
        context.setQuestion(StringUtils.trim(question.getQuestion()));
        context.setPathScope(question.getPathScope());
        context.setSessionId(question.getSessionId());
        context.setCheckpointId(question.getCheckpointId());
        context.setPlanTarget(question.getPlanTarget());
        context.setPlanRevision(question.getPlanRevision());
        context.setPlanStateVersion(question.getPlanStateVersion() == null ? 0L : question.getPlanStateVersion());
        context.setPlanBinding(question.getPlanBinding());
        context.setCollaborationMode(resolveMode(question, question.getSeedSnapshot()));
        context.setResolvedWorkspace(workspace.getRoot());
        context.setWorkspace(workspace.getWorkspace());
        context.setWorkspaceDisplayName(workspace.getDisplayName());
        AgentRuntimeProperties runProperties = context.runtimeProperties(properties);
        int maxSteps = question.getMaxSteps() == null ? runProperties.getMaxSteps() : question.getMaxSteps();
        context.setMaxSteps(maxSteps);
        context.setMaxAttempts(question.getMaxAttempts() == null
                ? Math.max(maxSteps * 3, maxSteps + 4)
                : Math.max(1, question.getMaxAttempts()));
        context.setStartedAt(Instant.now());
        context.setToolSteps(0);
        context.setModelAttempts(0);
        context.setParseErrors(0);
        context.setAllowedTools(normalizeAllowedTools(question.getAllowedTools()));
        context.setApprovalPolicy(resolveApprovalPolicy(question, runProperties));
        context.setPermissionPolicySnapshot(question.getInheritedPermissionPolicySnapshot());
        context.setSessionExecutionGrants(question.getInheritedSessionExecutionGrants());
        context.setSecurityScope(question.getInheritedSecurityScope() == null
                ? RootRunSecurityScope.create() : question.getInheritedSecurityScope());
        freezeAuthorization(context, question.isFullAccess());
        freezeEffectiveToolCatalog(context);
        applyInheritedSkills(context, question);
        context.setToolSpecs(SkillToolCatalogProjector.project(context, toolRegistry));
        applyAttemptRuntime(context, question);
    }

    private void applyAttemptRuntime(AgentContext context, AgentQuestion question) {
        if (StringUtils.isNotBlank(question.getModel())) {
            context.setCurrentModel(question.getModel());
        }
        if (StringUtils.isNotBlank(question.getProvider())) {
            context.setAttemptProvider(question.getProvider());
        }
        if (StringUtils.isNotBlank(question.getRuntimeIdentity())) {
            context.setAttemptRuntimeIdentity(question.getRuntimeIdentity());
        }
    }

    private void applyInheritedSkills(AgentContext context, AgentQuestion question) {
        if (question.getInheritedSkillCatalogSnapshot() != null) {
            context.setSkillCatalogSnapshot(question.getInheritedSkillCatalogSnapshot());
        }
        if (question.getInheritedActiveSkills() != null) {
            context.setActiveSkills(question.getInheritedActiveSkills());
        }
    }

    private List<String> normalizeAllowedTools(List<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String name : allowedTools) {
            if (name != null) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    normalized.add(trimmed);
                }
            }
        }
        return List.copyOf(normalized);
    }

    /** Freeze the exact catalog available at Run creation so restore cannot
     * acquire a Tool that the original Run did not have. */
    private void freezeEffectiveToolCatalog(AgentContext context) {
        List<cn.lunalhx.ai.domain.tool.model.ToolSpec> effective = toolRegistry.effectiveSpecs(
                context.getCollaborationMode(), context.getAllowedTools(), context.getExecutionProfile());
        context.setAllowedTools(effective.stream()
                .map(cn.lunalhx.ai.domain.tool.model.ToolSpec::getName)
                .distinct()
                .toList());
        context.setToolSpecs(effective);
    }

    private String resolveApprovalPolicy(AgentQuestion question, AgentRuntimeProperties runProperties) {
        if (question.getApprovalPolicy() != null && !question.getApprovalPolicy().isBlank()) {
            String p = question.getApprovalPolicy().trim();
            if ("ask".equalsIgnoreCase(p) || "auto".equalsIgnoreCase(p) || "never".equalsIgnoreCase(p)) {
                return p.toLowerCase();
            }
        }
        return StringUtils.defaultIfBlank(runProperties.getApprovalPolicy(), "ask");
    }

    private void freezeAuthorization(AgentContext context, boolean fullAccessRequested) {
        boolean delegate = context.getParentRunId() != null || context.getAgentDepth() > 0;
        Path workspace = context.getResolvedWorkspace();
        try {
            workspace = workspace == null ? null : workspace.toRealPath();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("workspace cannot be canonicalized for authorization", e);
        }
        ExecutionProfile baseProfile = fullAccessRequested && context.getCollaborationMode() == CollaborationMode.BUILD && !delegate
                ? ExecutionProfile.fullAccess(workspace)
                : ExecutionProfile.forRun(context.getCollaborationMode(), delegate).withWorkspace(workspace);
        RootRunSecurityScope scope = context.getSecurityScope();
        context.setExecutionProfile(baseProfile.kind() == cn.lunalhx.ai.domain.tool.model.ExecutionProfileKind.DANGER_FULL_ACCESS
                ? baseProfile : new ExecutionProfile(baseProfile.kind(), baseProfile.workspace(), baseProfile.workspaceAccess(),
                scope.homeRoot(), scope.temporaryRoot(), baseProfile.networkAllowed(), baseProfile.hostPrivateVisible(),
                baseProfile.externalGrants(), baseProfile.sandboxBackend()));
        if (context.getParentRunId() != null && context.getPermissionPolicySnapshot() != null) {
            return;
        }
        PermissionAction defaultAction = context.getExecutionProfile().kind()
                == cn.lunalhx.ai.domain.tool.model.ExecutionProfileKind.DANGER_FULL_ACCESS
                ? PermissionAction.ALLOW : switch (context.getApprovalPolicy()) {
            case "auto" -> PermissionAction.ALLOW;
            case "never" -> PermissionAction.DENY;
            default -> PermissionAction.ASK;
        };
        RunAuthorizationSource authorizationSource = new RunAuthorizationSource();
        RunAuthorizationSource.AuthorizationSources authorization = authorizationSource.loadRoot(workspace, defaultAction);
        context.setPermissionPolicySnapshot(authorization.policy());
        context.setPermissionGrants(authorizationSource.loadWorkspaceGrants(workspace));
        context.setExecutionGrants(authorizationSource.loadWorkspaceExecutionGrants(workspace));
        if (context.getExecutionProfile().kind()
                == cn.lunalhx.ai.domain.tool.model.ExecutionProfileKind.PLAN_SANDBOX) {
            context.setExecutionProfile(context.getExecutionProfile().withExternalGrants(
                    authorization.mavenRepositoryGrants()));
        }
    }


    private CollaborationMode resolveMode(AgentQuestion question, AgentContextSnapshot previous) {
        if (question.getCollaborationMode() != null) {
            return question.getCollaborationMode();
        }
        if (previous != null && previous.getRunModeSnapshot() != null) {
            return previous.getRunModeSnapshot();
        }
        return CollaborationMode.BUILD;
    }
}
