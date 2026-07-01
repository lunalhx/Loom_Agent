package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentWorkspace;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AgentContextFactory {

    private final AgentRuntimeProperties properties;
    private final AgentWorkspaceResolver workspaceResolver;
    private final List<ToolSpec> toolSpecs;
    private final boolean subAgentAvailable;

    public AgentContextFactory(AgentRuntimeProperties properties,
                               AgentWorkspaceResolver workspaceResolver,
                               List<ToolSpec> toolSpecs,
                               boolean subAgentAvailable) {
        this.properties = properties;
        this.workspaceResolver = workspaceResolver;
        this.toolSpecs = List.copyOf(toolSpecs);
        this.subAgentAvailable = subAgentAvailable;
    }

    public AgentContext create(AgentQuestion question) {
        AgentWorkspace workspace = workspaceResolver.resolve(question.getWorkspace());
        String runId = StringUtils.defaultIfBlank(question.getRunId(), UUID.randomUUID().toString());
        AgentContext context = new AgentContext();
        applyCommonFields(context, question, workspace, runId);
        if (StringUtils.isBlank(question.getConversationId())) {
            context.setConversationId(UUID.randomUUID().toString());
        }
        context.getDynamicText().appendUserTask(context.getQuestion());
        context.setRequestedSkills(question.getSkills());
        return context;
    }

    public AgentContext createContinuation(AgentQuestion question, AgentContextSnapshot previous) {
        AgentWorkspace workspace = workspaceResolver.resolve(question.getWorkspace());
        String runId = StringUtils.defaultIfBlank(question.getRunId(), UUID.randomUUID().toString());
        String requestId = StringUtils.defaultIfBlank(question.getRequestId(), UUID.randomUUID().toString());

        AgentContext context = new AgentContext();
        context.setRunId(runId);
        context.setParentRunId(question.getParentRunId());
        // 续聊沿用同一会话的根 run，优先从历史快照恢复，避免 rootRunId 为 null 导致
        // ContextArtifact 持久化时违反 agent_context_artifact.root_run_id 非空约束。
        String rootRunId = previous != null && StringUtils.isNotBlank(previous.getRootRunId())
                ? previous.getRootRunId()
                : StringUtils.defaultIfBlank(question.getRootRunId(), runId);
        context.setRootRunId(rootRunId);
        context.setRequestId(requestId);
        context.setConversationId(question.getConversationId());
        context.setAgentRole(question.getAgentRole());
        context.setAgentDepth(question.getAgentDepth() == null ? 0 : question.getAgentDepth());
        context.setChildOrdinal(question.getChildOrdinal() == null ? 0 : question.getChildOrdinal());
        context.setQuestion(StringUtils.trim(question.getQuestion()));
        context.setPathScope(question.getPathScope());
        context.setResolvedWorkspace(workspace.getRoot());
        context.setWorkspace(workspace.getWorkspace());
        context.setWorkspaceDisplayName(workspace.getDisplayName());
        context.setMaxSteps(question.getMaxSteps() == null ? properties.getMaxSteps() : question.getMaxSteps());
        context.setStartedAt(Instant.now());
        context.setStep(0);
        context.setParseErrors(0);
        context.setSubAgentSpawnAllowed(shouldAllowSubAgents(question, context));
        List<ToolSpec> specs = new java.util.ArrayList<>(toolSpecs);
        if (context.isSubAgentSpawnAllowed()) {
            specs.add(SubAgentToolSpecs.spawnAgentsSpec());
        }
        context.setToolSpecs(specs);
        context.setTraceId(StringUtils.defaultIfBlank(question.getTraceId(), context.getRootRunId()));

        initStepBudget(context, question);

        if (previous != null && previous.getDynamicTextEntries() != null) {
            context.getDynamicText().replaceEntries(previous.getDynamicTextEntries());
        }
        context.getDynamicText().appendUserTask(context.getQuestion());
        context.setRequestedSkills(question.getSkills());
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
        context.setAgentRole(question.getAgentRole());
        context.setAgentDepth(question.getAgentDepth() == null ? 0 : question.getAgentDepth());
        context.setChildOrdinal(question.getChildOrdinal() == null ? 0 : question.getChildOrdinal());
        context.setQuestion(StringUtils.trim(question.getQuestion()));
        context.setPathScope(question.getPathScope());
        context.setResolvedWorkspace(workspace.getRoot());
        context.setWorkspace(workspace.getWorkspace());
        context.setWorkspaceDisplayName(workspace.getDisplayName());
        context.setMaxSteps(question.getMaxSteps() == null ? properties.getMaxSteps() : question.getMaxSteps());
        context.setStartedAt(Instant.now());
        context.setSubAgentSpawnAllowed(shouldAllowSubAgents(question, context));
        List<ToolSpec> specs = new java.util.ArrayList<>(toolSpecs);
        if (context.isSubAgentSpawnAllowed()) {
            specs.add(SubAgentToolSpecs.spawnAgentsSpec());
        }
        context.setToolSpecs(specs);
        initStepBudget(context, question);
        if (StringUtils.isNotBlank(question.getModel())) {
            context.setCurrentModel(question.getModel());
        }
    }

    private void initStepBudget(AgentContext context, AgentQuestion question) {
        AgentRuntimeProperties.StepBudgetProperties stepBudget = properties.getStepBudget();
        if (stepBudget == null || !Boolean.TRUE.equals(stepBudget.getContinuationEnabled())) {
            context.setMaxSegments(1);
            context.setMaxTotalSteps(context.getMaxSteps());
            context.setSegmentIndex(0);
            context.setSegmentStartStep(0);
            return;
        }
        boolean isRoot = StringUtils.isBlank(question.getParentRunId());
        if (isRoot) {
            context.setMaxSegments(question.getMaxSegments() != null
                    ? question.getMaxSegments() : stepBudget.getMaxSegments());
        } else {
            int configMax = stepBudget.getMaxSegments() != null ? stepBudget.getMaxSegments() : 5;
            int childMax = stepBudget.getChildMaxSegments() != null ? stepBudget.getChildMaxSegments() : 2;
            context.setMaxSegments(Math.min(configMax, childMax));
        }
        context.setMaxTotalSteps(stepBudget.getMaxTotalSteps() != null
                ? stepBudget.getMaxTotalSteps() : 150);
        context.setSegmentIndex(0);
        context.setSegmentStartStep(0);
    }

    public AgentContext prepareCheckpointResume(AgentContext context, String workspace, Long checkpointVersion) {
        restoreWorkspace(context, workspace);
        context.setStartedAt(Instant.now());
        context.setCheckpointVersion(checkpointVersion);
        context.setSubAgentSpawnAllowed(context.isSubAgentSpawnAllowed() && subAgentAvailable);
        return context;
    }

    public AgentContext prepareApprovalResume(AgentContext context, PendingApproval approval) {
        restoreWorkspace(context, approval.getResolvedWorkspace() == null ? null : approval.getResolvedWorkspace().toString());
        context.setWorkspace(approval.getWorkspace());
        context.setWorkspaceDisplayName(approval.getWorkspaceDisplayName());
        context.setStartedAt(Instant.now());
        context.setPendingApprovalId(null);
        context.setSubAgentSpawnAllowed(context.isSubAgentSpawnAllowed() && subAgentAvailable);
        return context;
    }

    private boolean shouldAllowSubAgents(AgentQuestion question, AgentContext context) {
        boolean requested = question.getSubAgentSpawnAllowed() == null || Boolean.TRUE.equals(question.getSubAgentSpawnAllowed());
        return requested
                && subAgentAvailable
                && Boolean.TRUE.equals(properties.getSubAgentEnabled())
                && context.getAgentDepth() < Math.max(1, properties.getSubAgentMaxDepth() == null ? 1 : properties.getSubAgentMaxDepth());
    }

    private void restoreWorkspace(AgentContext context, String workspace) {
        AgentWorkspace resolved = workspaceResolver.resolve(workspace);
        context.setResolvedWorkspace(resolved.getRoot());
        context.setWorkspace(resolved.getWorkspace());
        context.setWorkspaceDisplayName(resolved.getDisplayName());
    }
}
