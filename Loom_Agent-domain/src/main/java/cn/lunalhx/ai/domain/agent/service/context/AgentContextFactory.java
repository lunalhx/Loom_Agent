package cn.lunalhx.ai.domain.agent.service.context;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistory;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRuntimeConfigSource;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentWorkspace;
import cn.lunalhx.ai.domain.agent.service.workspace.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;
import cn.lunalhx.ai.domain.tool.model.PermissionRule;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.nio.file.Path;
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

    public AgentContextFactory(AgentRuntimeProperties properties,
                               AgentWorkspaceResolver workspaceResolver,
                               AgentRuntimeConfigSource runtimeConfigSource,
                               ToolRegistry toolRegistry) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.workspaceResolver = Objects.requireNonNull(workspaceResolver,
                "workspaceResolver must not be null");
        this.runtimeConfigSource = Objects.requireNonNull(runtimeConfigSource,
                "runtimeConfigSource must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
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
        if (previous == null) {
            return;
        }
        previous.ensureCurrentShape();
        if (previous.getLedgerEntries() != null && !previous.getLedgerEntries().isEmpty()) {
            context.setConversationHistory(ConversationHistory.fromPersisted(
                    new ArrayList<>(previous.getLedgerEntries()),
                    previous.getLedgerNextSequence()));
        }
        if (previous.getWorkingMemory() != null) {
            context.setWorkingMemory(previous.getWorkingMemory());
        }
        context.setStablePrefix(previous.getStablePrefix());
        context.setGeneration(Math.max(0, previous.getGeneration()));
        // The new user question joins the seeded ledger as raw user input so
        // resumed sessions keep an append-only record of every request.
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
        context.setToolSpecs(toolRegistry.effectiveSpecs(
                context.getCollaborationMode(), context.getAllowedTools()));
        context.setApprovalPolicy(resolveApprovalPolicy(question, runProperties));
        context.setPermissionPolicySnapshot(question.getInheritedPermissionPolicySnapshot());
        freezeAuthorization(context);
        context.setTraceId(StringUtils.defaultIfBlank(question.getTraceId(), context.getRootRunId()));

        if (previous != null) {
            if (previous.getLedgerEntries() != null && !previous.getLedgerEntries().isEmpty()) {
                context.setConversationHistory(ConversationHistory.fromPersisted(
                        new ArrayList<>(previous.getLedgerEntries()),
                        previous.getLedgerNextSequence()));
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

        if (StringUtils.isNotBlank(question.getModel())) {
            context.setCurrentModel(question.getModel());
        }
        return context;
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
        context.setToolSpecs(toolRegistry.effectiveSpecs(
                context.getCollaborationMode(), context.getAllowedTools()));
        context.setApprovalPolicy(resolveApprovalPolicy(question, runProperties));
        context.setPermissionPolicySnapshot(question.getInheritedPermissionPolicySnapshot());
        freezeAuthorization(context);
        if (StringUtils.isNotBlank(question.getModel())) {
            context.setCurrentModel(question.getModel());
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

    private String resolveApprovalPolicy(AgentQuestion question, AgentRuntimeProperties runProperties) {
        if (question.getApprovalPolicy() != null && !question.getApprovalPolicy().isBlank()) {
            String p = question.getApprovalPolicy().trim();
            if ("ask".equalsIgnoreCase(p) || "auto".equalsIgnoreCase(p) || "never".equalsIgnoreCase(p)) {
                return p.toLowerCase();
            }
        }
        return StringUtils.defaultIfBlank(runProperties.getApprovalPolicy(), "ask");
    }

    private void freezeAuthorization(AgentContext context) {
        boolean delegate = context.getParentRunId() != null || context.getAgentDepth() > 0;
        Path workspace = context.getResolvedWorkspace();
        try {
            workspace = workspace == null ? null : workspace.toRealPath();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("workspace cannot be canonicalized for authorization", e);
        }
        context.setExecutionProfile(ExecutionProfile.forRun(context.getCollaborationMode(), delegate)
                .withWorkspace(workspace));
        // A delegate inherits the root's already validated snapshot; it never
        // re-reads policy sources or applies a child default.
        if (context.getParentRunId() != null && context.getPermissionPolicySnapshot() != null) {
            return;
        }
        PermissionAction defaultAction = switch (context.getApprovalPolicy()) {
            case "auto" -> PermissionAction.ALLOW;
            case "never" -> PermissionAction.DENY;
            default -> PermissionAction.ASK;
        };
        // The safety floor may silently allow only trusted structured repository reads.
        // All mutation and unknown tools remain governed by the selected default/rules.
        List<PermissionRule> builtIn = List.of(
                new PermissionRule("builtin-read-file", "builtin", "read_file",
                        PermissionRule.MatcherKind.TOOL, "read_file", PermissionAction.ALLOW),
                new PermissionRule("builtin-list-files", "builtin", "list_files",
                        PermissionRule.MatcherKind.TOOL, "list_files", PermissionAction.ALLOW),
                new PermissionRule("builtin-search", "builtin", "search",
                        PermissionRule.MatcherKind.TOOL, "search", PermissionAction.ALLOW),
                new PermissionRule("builtin-delegate", "builtin", "delegate",
                        PermissionRule.MatcherKind.TOOL, "delegate", PermissionAction.ALLOW));
        context.setPermissionPolicySnapshot(new PermissionPolicySnapshot(defaultAction, builtIn, List.of("builtin")));
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

    private void restoreWorkspace(AgentContext context, String workspace) {
        AgentWorkspace resolved = workspaceResolver.resolve(workspace);
        context.setResolvedWorkspace(resolved.getRoot());
        context.setWorkspace(resolved.getWorkspace());
        context.setWorkspaceDisplayName(resolved.getDisplayName());
    }
}
