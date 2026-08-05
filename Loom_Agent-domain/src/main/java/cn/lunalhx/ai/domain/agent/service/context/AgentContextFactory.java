package cn.lunalhx.ai.domain.agent.service.context;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistory;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRuntimeConfigSource;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunConfig;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentWorkspace;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.workspace.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

public final class AgentContextFactory {

    private final AgentRuntimeProperties properties;
    private final AgentWorkspaceResolver workspaceResolver;
    private final List<ToolSpec> toolSpecs;
    private final ConversationHistoryAppendService ledgerAppendService;
    private final AgentRuntimeConfigSource runtimeConfigSource;

    public AgentContextFactory(AgentRuntimeProperties properties,
                               AgentWorkspaceResolver workspaceResolver,
                               List<ToolSpec> toolSpecs) {
        this(properties, workspaceResolver, toolSpecs, null);
    }

    public AgentContextFactory(AgentRuntimeProperties properties,
                               AgentWorkspaceResolver workspaceResolver,
                               List<ToolSpec> toolSpecs,
                               ConversationHistoryAppendService ledgerAppendService) {
        this(properties, workspaceResolver, toolSpecs, ledgerAppendService,
                () -> AgentRunConfig.startup(properties,
                        new cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties()));
    }

    public AgentContextFactory(AgentRuntimeProperties properties,
                               AgentWorkspaceResolver workspaceResolver,
                               List<ToolSpec> toolSpecs,
                               ConversationHistoryAppendService ledgerAppendService,
                               AgentRuntimeConfigSource runtimeConfigSource) {
        this.properties = properties;
        this.workspaceResolver = workspaceResolver;
        this.toolSpecs = List.copyOf(toolSpecs);
        this.ledgerAppendService = ledgerAppendService;
        this.runtimeConfigSource = runtimeConfigSource;
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
        return context;
    }

    public AgentContext createContinuation(AgentQuestion question, AgentContextSnapshot previous) {
        AgentWorkspace workspace = workspaceResolver.resolve(question.getWorkspace());
        String runId = StringUtils.defaultIfBlank(question.getRunId(), UUID.randomUUID().toString());
        String requestId = StringUtils.defaultIfBlank(question.getRequestId(), UUID.randomUUID().toString());

        AgentContext context = new AgentContext();
        context.setRunConfig(runtimeConfigSource.captureRunConfig());
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
        context.setResolvedWorkspace(workspace.getRoot());
        context.setWorkspace(workspace.getWorkspace());
        context.setWorkspaceDisplayName(workspace.getDisplayName());
        AgentRuntimeProperties runProperties = context.runtimeProperties(properties);
        int maxSteps = question.getMaxSteps() == null ? runProperties.getMaxSteps() : question.getMaxSteps();
        context.setMaxSteps(maxSteps);
        context.setMaxAttempts(Math.max(maxSteps * 3, maxSteps + 4));
        context.setStartedAt(Instant.now());
        context.setToolSteps(0);
        context.setModelAttempts(0);
        context.setParseErrors(0);
        context.setToolSpecs(toolSpecs);
        context.setAllowedTools(normalizeAllowedTools(question.getAllowedTools()));
        context.setApprovalPolicy(resolveApprovalPolicy(question, runProperties));
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
        context.setResolvedWorkspace(workspace.getRoot());
        context.setWorkspace(workspace.getWorkspace());
        context.setWorkspaceDisplayName(workspace.getDisplayName());
        AgentRuntimeProperties runProperties = context.runtimeProperties(properties);
        int maxSteps = question.getMaxSteps() == null ? runProperties.getMaxSteps() : question.getMaxSteps();
        context.setMaxSteps(maxSteps);
        context.setMaxAttempts(Math.max(maxSteps * 3, maxSteps + 4));
        context.setStartedAt(Instant.now());
        context.setToolSteps(0);
        context.setModelAttempts(0);
        context.setParseErrors(0);
        context.setToolSpecs(toolSpecs);
        context.setAllowedTools(normalizeAllowedTools(question.getAllowedTools()));
        context.setApprovalPolicy(resolveApprovalPolicy(question, runProperties));
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

    public AgentContext prepareCheckpointResume(AgentContext context, String workspace, Long checkpointVersion) {
        context.setRunConfig(runtimeConfigSource.captureRunConfig());
        restoreWorkspace(context, workspace);
        context.setStartedAt(Instant.now());
        context.setCheckpointVersion(checkpointVersion);
        context.setToolSpecs(toolSpecs);
        if (context.getMaxAttempts() <= 0 && context.getMaxSteps() > 0) {
            context.setMaxAttempts(Math.max(context.getMaxSteps() * 3, context.getMaxSteps() + 4));
        }
        return context;
    }

    public AgentContext prepareApprovalResume(AgentContext context, PendingApproval approval) {
        context.setRunConfig(runtimeConfigSource.captureRunConfig());
        restoreWorkspace(context, approval.getResolvedWorkspace() == null ? null : approval.getResolvedWorkspace().toString());
        context.setWorkspace(approval.getWorkspace());
        context.setWorkspaceDisplayName(approval.getWorkspaceDisplayName());
        context.setStartedAt(Instant.now());
        context.setPendingApprovalId(null);
        context.setToolSpecs(toolSpecs);
        return context;
    }

    private void restoreWorkspace(AgentContext context, String workspace) {
        AgentWorkspace resolved = workspaceResolver.resolve(workspace);
        context.setResolvedWorkspace(resolved.getRoot());
        context.setWorkspace(resolved.getWorkspace());
        context.setWorkspaceDisplayName(resolved.getDisplayName());
    }
}
