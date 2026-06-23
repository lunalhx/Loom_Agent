package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookRegistry;
import cn.lunalhx.ai.domain.agent.flow.hook.CheckpointAgentHook;
import cn.lunalhx.ai.domain.agent.flow.node.ApprovalGateNode;
import cn.lunalhx.ai.domain.agent.flow.node.FailNode;
import cn.lunalhx.ai.domain.agent.flow.node.FinalAnswerNode;
import cn.lunalhx.ai.domain.agent.flow.node.ModelCallNode;
import cn.lunalhx.ai.domain.agent.flow.node.ObservationNode;
import cn.lunalhx.ai.domain.agent.flow.node.DecisionNode;
import cn.lunalhx.ai.domain.agent.flow.node.PlannerNode;
import cn.lunalhx.ai.domain.agent.flow.node.RenderPromptNode;
import cn.lunalhx.ai.domain.agent.flow.node.ReplanGuardNode;
import cn.lunalhx.ai.domain.agent.flow.node.ReplanNode;
import cn.lunalhx.ai.domain.agent.flow.node.StartNode;
import cn.lunalhx.ai.domain.agent.flow.node.SubAgentDispatchNode;
import cn.lunalhx.ai.domain.agent.flow.node.ToolDispatchNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunKind;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentWorkspace;
import cn.lunalhx.ai.domain.agent.model.valobj.WorkspaceResolutionException;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

@Slf4j
public class DefaultAgentLoopService implements AgentLoopService {

    private final AgentRuntimeProperties properties;
    private final ApprovalStore approvalStore;
    private final AgentWorkspaceResolver workspaceResolver;
    private final AgentCheckpointRepository checkpointRepository;
    private final AgentHookRegistry hookRegistry;
    private final Executor executor;
    private final SubAgentCoordinator subAgentCoordinator;
    private final TraceRecorder traceRecorder;
    private final BudgetGuard budgetGuard;
    private final AgentMetrics agentMetrics;
    private final ContextWindowManager contextWindowManager;
    private final List<ToolSpec> toolSpecs;
    private final Map<String, AgentNode> nodes;

    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor) {
        this(modelGateway, toolRegistry, new InMemoryApprovalStore(), new AgentWorkspaceResolver(properties),
                new InMemoryAgentRunRepository(), new InMemoryAgentCheckpointRepository(), properties, objectMapper, executor);
    }

    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   ApprovalStore approvalStore,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor) {
        this(modelGateway, toolRegistry, approvalStore, new AgentWorkspaceResolver(properties),
                new InMemoryAgentRunRepository(), new InMemoryAgentCheckpointRepository(), properties, objectMapper, executor);
    }

    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   ApprovalStore approvalStore,
                                   AgentWorkspaceResolver workspaceResolver,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor) {
        this(modelGateway, toolRegistry, approvalStore, workspaceResolver,
                new InMemoryAgentRunRepository(), new InMemoryAgentCheckpointRepository(), properties, objectMapper, executor);
    }

    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   ApprovalStore approvalStore,
                                   AgentWorkspaceResolver workspaceResolver,
                                   AgentRunRepository runRepository,
                                   AgentCheckpointRepository checkpointRepository,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor) {
        this(modelGateway, toolRegistry, approvalStore, workspaceResolver, runRepository, checkpointRepository,
                properties, objectMapper, executor, null);
    }

    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   ApprovalStore approvalStore,
                                   AgentWorkspaceResolver workspaceResolver,
                                   AgentRunRepository runRepository,
                                   AgentCheckpointRepository checkpointRepository,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor,
                                   SubAgentCoordinator subAgentCoordinator) {
        this(modelGateway, toolRegistry, approvalStore, workspaceResolver, runRepository, checkpointRepository,
                properties, objectMapper, executor, subAgentCoordinator,
                new InMemoryTraceRecorder(), new DefaultBudgetGuard(properties), new NoopAgentMetrics());
    }

    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   ApprovalStore approvalStore,
                                   AgentWorkspaceResolver workspaceResolver,
                                   AgentRunRepository runRepository,
                                   AgentCheckpointRepository checkpointRepository,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor,
                                   SubAgentCoordinator subAgentCoordinator,
                                   TraceRecorder traceRecorder,
                                   BudgetGuard budgetGuard) {
        this(modelGateway, toolRegistry, approvalStore, workspaceResolver, runRepository, checkpointRepository,
                properties, objectMapper, executor, subAgentCoordinator, traceRecorder, budgetGuard, new NoopAgentMetrics());
    }

    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   ApprovalStore approvalStore,
                                   AgentWorkspaceResolver workspaceResolver,
                                   AgentRunRepository runRepository,
                                   AgentCheckpointRepository checkpointRepository,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor,
                                   SubAgentCoordinator subAgentCoordinator,
                                   TraceRecorder traceRecorder,
                                   BudgetGuard budgetGuard,
                                   AgentMetrics agentMetrics) {
        this(modelGateway, toolRegistry, approvalStore, workspaceResolver, runRepository, checkpointRepository,
                properties, objectMapper, executor, subAgentCoordinator, traceRecorder, budgetGuard, agentMetrics,
                ContextWindowManager.noop(properties));
    }

    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   ApprovalStore approvalStore,
                                   AgentWorkspaceResolver workspaceResolver,
                                   AgentRunRepository runRepository,
                                   AgentCheckpointRepository checkpointRepository,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor,
                                   SubAgentCoordinator subAgentCoordinator,
                                   TraceRecorder traceRecorder,
                                   BudgetGuard budgetGuard,
                                   AgentMetrics agentMetrics,
                                   ContextWindowManager contextWindowManager) {
        this.properties = properties;
        this.approvalStore = approvalStore;
        this.workspaceResolver = workspaceResolver;
        this.checkpointRepository = checkpointRepository;
        this.executor = executor;
        this.subAgentCoordinator = subAgentCoordinator;
        this.traceRecorder = traceRecorder == null ? new InMemoryTraceRecorder() : traceRecorder;
        this.budgetGuard = budgetGuard == null ? new DefaultBudgetGuard(properties) : budgetGuard;
        this.agentMetrics = agentMetrics == null ? new NoopAgentMetrics() : agentMetrics;
        this.contextWindowManager = contextWindowManager == null ? ContextWindowManager.noop(properties) : contextWindowManager;
        this.toolSpecs = toolRegistry.specs();
        this.hookRegistry = new AgentHookRegistry(List.of(new CheckpointAgentHook(runRepository, checkpointRepository, objectMapper)));
        List<AgentNode> nodeList = new java.util.ArrayList<>(List.of(
                new StartNode(),
                new PlannerNode(),
                new RenderPromptNode(this.contextWindowManager),
                new ModelCallNode(modelGateway, properties, this.traceRecorder, this.budgetGuard, this.contextWindowManager),
                new DecisionNode(objectMapper, toolRegistry, properties),
                new ApprovalGateNode(toolRegistry, approvalStore, properties),
                new ToolDispatchNode(toolRegistry, properties, hookRegistry, this.contextWindowManager),
                new ObservationNode(),
                new ReplanGuardNode(),
                new ReplanNode(modelGateway, properties, objectMapper, this.traceRecorder, this.budgetGuard),
                new FinalAnswerNode(),
                new FailNode()));
        if (subAgentCoordinator != null) {
            nodeList.add(new SubAgentDispatchNode(subAgentCoordinator, properties));
        }
        this.nodes = registerNodes(nodeList);
    }

    @Override
    public Flux<AgentEvent> ask(AgentQuestion question) {
        return Flux.create(sink -> executor.execute(() -> {
            try {
                AgentContext context = toContext(question);
                emit(sink, hookRegistry.trigger(AgentHookEvent.USER_PROMPT_SUBMIT, AgentHookContext.builder()
                        .agentContext(context)
                        .node(AgentNodeNames.START)
                        .reason("user_prompt_submit")
                        .build()));
                runLoop(context, AgentNodeNames.START, sink);
            } catch (WorkspaceResolutionException e) {
                emit(sink, List.of(workspaceError(e)));
                sink.complete();
            } catch (Exception e) {
                log.error("Agent loop failed before terminal event, workspace={}, question={}",
                        question == null ? null : question.getWorkspace(),
                        question == null ? null : StringUtils.abbreviate(question.getQuestion(), 200),
                        e);
                emit(sink, List.of(AgentEvent.builder()
                        .type(AgentEventType.ERROR)
                        .code("agent_error")
                        .message("Agent 执行失败")
                        .build()));
                sink.complete();
            }
        }), FluxSink.OverflowStrategy.BUFFER);
    }

    @Override
    public Flux<AgentEvent> resume(String approvalId, ApprovalDecision decision, String reason) {
        return Flux.create(sink -> executor.execute(() -> resumeLoop(approvalId, decision, reason, sink)), FluxSink.OverflowStrategy.BUFFER);
    }

    @Override
    public Flux<AgentEvent> resumeRun(String runId) {
        return Flux.create(sink -> executor.execute(() -> resumeRunLoop(runId, sink)), FluxSink.OverflowStrategy.BUFFER);
    }

    private void resumeLoop(String approvalId,
                            ApprovalDecision decision,
                            String reason,
                            FluxSink<AgentEvent> sink) {
        PendingApproval approval = approvalStore.consume(approvalId).orElse(null);
        if (approval == null) {
            emit(sink, List.of(AgentEvent.builder()
                    .type(AgentEventType.ERROR)
                    .approvalId(approvalId)
                    .code("approval_not_found")
                    .message("审批不存在或已过期")
                    .build()));
            sink.complete();
            return;
        }

        AgentContext context = approval.getContext();
        if (StringUtils.isNotBlank(approval.getRunId())) {
            AgentCheckpoint checkpoint = checkpointRepository.latest(approval.getRunId()).orElse(null);
            if (checkpoint != null && checkpoint.getContextSnapshot() != null) {
                context = checkpoint.getContextSnapshot().restore();
            }
        }
        context.setSubAgentSpawnAllowed(context.isSubAgentSpawnAllowed() && subAgentCoordinator != null);
        restoreWorkspace(context, approval.getResolvedWorkspace() == null ? null : approval.getResolvedWorkspace().toString());
        context.setWorkspace(approval.getWorkspace());
        context.setWorkspaceDisplayName(approval.getWorkspaceDisplayName());
        context.setStartedAt(Instant.now());
        context.setPendingApprovalId(null);
        emit(sink, List.of(resumeStartedEvent(context)));
        if (decision == ApprovalDecision.APPROVE) {
            runLoop(context, AgentNodeNames.TOOL_DISPATCH, sink);
            return;
        }

        context.setStep(context.getStep() + 1);
        context.setToolResult(ToolResult.failure(
                "approval_rejected",
                StringUtils.defaultIfBlank(reason, "用户拒绝执行该写操作"),
                0L));
        context.getDynamicText().appendAssistantAction(context.getStep(), AgentNodeNames.APPROVAL_GATE, context.getDecision());
        runLoop(context, AgentNodeNames.OBSERVATION, sink);
    }

    private void resumeRunLoop(String runId, FluxSink<AgentEvent> sink) {
        AgentCheckpoint checkpoint = checkpointRepository.latest(runId).orElse(null);
        if (checkpoint == null || checkpoint.getContextSnapshot() == null) {
            emit(sink, List.of(AgentEvent.builder()
                    .type(AgentEventType.ERROR)
                    .runId(runId)
                    .code("checkpoint_not_found")
                    .message("未找到可恢复的 checkpoint")
                    .build()));
            sink.complete();
            return;
        }
        try {
            AgentContext context = checkpoint.getContextSnapshot().restore();
            context.setSubAgentSpawnAllowed(context.isSubAgentSpawnAllowed() && subAgentCoordinator != null);
            restoreWorkspace(context, context.getResolvedWorkspace() == null ? null : context.getResolvedWorkspace().toString());
            context.setStartedAt(Instant.now());
            context.setCheckpointVersion(checkpoint.getVersion());
            emit(sink, List.of(resumeStartedEvent(context)));
            if (AgentNodeNames.APPROVAL_GATE.equals(checkpoint.getCurrentNode()) && StringUtils.isNotBlank(context.getPendingApprovalId())) {
                PendingApproval approval = approvalStore.find(context.getPendingApprovalId()).orElse(null);
                if (approval != null) {
                    emit(sink, List.of(approvalRequiredEvent(context, approval)));
                    sink.complete();
                    return;
                }
            }
            String currentNode = StringUtils.defaultIfBlank(checkpoint.getCurrentNode(), AgentNodeNames.RENDER_PROMPT);
            if (context.isUnsafeResumeRequired() || requiresResumeReplan(currentNode)) {
                context.setUnsafeResumeRequired(true);
                currentNode = AgentNodeNames.REPLAN_GUARD;
            }
            runLoop(context, currentNode, sink);
        } catch (WorkspaceResolutionException e) {
            emit(sink, List.of(workspaceError(e)));
            sink.complete();
        }
    }

    private void runLoop(AgentContext context, String currentNode, FluxSink<AgentEvent> sink) {
        while (!sink.isCancelled()) {
            if (isTotalTimeout(context)) {
                fail(context, AgentStopReason.TIMEOUT, "agent_timeout", "Agent 执行超时");
                currentNode = AgentNodeNames.FAIL;
            }

            AgentNode node = nodes.get(currentNode);
            context.setCurrentNode(currentNode);
            if (node == null) {
                fail(context, AgentStopReason.MODEL_ERROR, "node_not_found", "未知节点：" + currentNode);
                node = nodes.get(AgentNodeNames.FAIL);
            }

            String parentSpanId = context.getCurrentSpanId();
            String spanId = traceRecorder.recordNodeStart(context, node, parentSpanId);
            context.setParentSpanId(parentSpanId);
            context.setCurrentSpanId(spanId);
            long startedAt = System.currentTimeMillis();
            putNodeMdc(context, node.name());
            emit(sink, hookRegistry.trigger(AgentHookEvent.BEFORE_NODE, AgentHookContext.builder()
                    .agentContext(context)
                    .node(node.name())
                    .reason("before_node:" + node.name())
                    .build()));
            emit(sink, List.of(nodeStartEvent(context, node)));
            NodeResult result = node.apply(context);
            emit(sink, result.getEvents());
            recordContextCompactedEvents(context, node, startedAt, result.getEvents());
            String nextNode = result.isTerminal() ? node.name() : result.getNextNode();
            long durationMs = System.currentTimeMillis() - startedAt;
            String status = nodeStatus(context, result);
            traceRecorder.recordNodeEnd(context, node, spanId, status,
                    durationMs, "nextNode=" + nextNode, null);
            agentMetrics.recordNodeDuration(node.name(), status, durationMs);
            emit(sink, hookRegistry.trigger(AgentHookEvent.AFTER_NODE, AgentHookContext.builder()
                    .agentContext(context)
                    .node(node.name())
                    .nextNode(nextNode)
                    .reason("after_node:" + node.name())
                    .build()));
            if (result.isTerminal()) {
                traceRecorder.recordStop(context, stopStatus(context), stopSummary(context));
                agentMetrics.recordRun(runKind(context), stopStatus(context), context.getErrorCode());
                emit(sink, hookRegistry.trigger(AgentHookEvent.STOP, AgentHookContext.builder()
                        .agentContext(context)
                        .node(node.name())
                        .reason("stop:" + node.name())
                        .build()));
                sink.complete();
                MDC.clear();
                return;
            }
            MDC.clear();
            currentNode = nextNode;
        }
    }

    private boolean requiresResumeReplan(String currentNode) {
        return AgentNodeNames.APPROVAL_GATE.equals(currentNode)
                || AgentNodeNames.TOOL_DISPATCH.equals(currentNode);
    }

    private void recordContextCompactedEvents(AgentContext context, AgentNode node, long startedAt, List<AgentEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (AgentEvent event : events) {
            if (event.getType() == AgentEventType.CONTEXT_COMPACTED) {
                traceRecorder.recordModelGatewayEvent(context,
                        AgentEventType.CONTEXT_COMPACTED.eventName(),
                        node.name(),
                        "success",
                        System.currentTimeMillis() - startedAt,
                        event.getMessage(),
                        null,
                        event.getMetadata());
            }
        }
    }

    private AgentContext toContext(AgentQuestion question) {
        AgentWorkspace workspace = workspaceResolver.resolve(question.getWorkspace());
        String runId = StringUtils.defaultIfBlank(question.getRunId(), UUID.randomUUID().toString());
        AgentContext context = new AgentContext();
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
        context.getDynamicText().appendUserTask(context.getQuestion());
        return context;
    }

    private boolean shouldAllowSubAgents(AgentQuestion question, AgentContext context) {
        boolean requested = question.getSubAgentSpawnAllowed() == null || Boolean.TRUE.equals(question.getSubAgentSpawnAllowed());
        return requested
                && subAgentCoordinator != null
                && Boolean.TRUE.equals(properties.getSubAgentEnabled())
                && context.getAgentDepth() < Math.max(1, properties.getSubAgentMaxDepth() == null ? 1 : properties.getSubAgentMaxDepth());
    }

    private void restoreWorkspace(AgentContext context, String workspace) {
        AgentWorkspace resolved = workspaceResolver.resolve(workspace);
        context.setResolvedWorkspace(resolved.getRoot());
        context.setWorkspace(resolved.getWorkspace());
        context.setWorkspaceDisplayName(resolved.getDisplayName());
    }

    private Map<String, AgentNode> registerNodes(List<AgentNode> nodeList) {
        Map<String, AgentNode> registeredNodes = new LinkedHashMap<>();
        for (AgentNode node : nodeList) {
            if (registeredNodes.containsKey(node.name())) {
                throw new IllegalStateException("重复的 Agent 节点：" + node.name());
            }
            registeredNodes.put(node.name(), node);
        }
        return registeredNodes;
    }

    private AgentEvent nodeStartEvent(AgentContext context, AgentNode node) {
        return AgentEvent.builder()
                .type(AgentEventType.NODE_START)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .node(node.name())
                .nodeInputs(node.inputKeys())
                .build();
    }

    private AgentEvent resumeStartedEvent(AgentContext context) {
        return AgentEvent.builder()
                .type(AgentEventType.RESUME_STARTED)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .checkpointVersion(context.getCheckpointVersion())
                .plan(context.getPlan() == null ? null : context.getPlan().toView())
                .build();
    }

    private AgentEvent approvalRequiredEvent(AgentContext context, PendingApproval approval) {
        return AgentEvent.builder()
                .type(AgentEventType.APPROVAL_REQUIRED)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(approval.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .step(context.getStep() + 1)
                .tool(approval.getTool())
                .input(approval.getInput())
                .approvalId(approval.getApprovalId())
                .permissionLevel(approval.getPermissionLevel() == null ? null : approval.getPermissionLevel().name())
                .riskReason(approval.getRiskReason())
                .operationPreview(approval.getOperationPreview())
                .expiresAt(approval.getExpiresAt())
                .build();
    }

    private AgentEvent workspaceError(WorkspaceResolutionException e) {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .requestId(UUID.randomUUID().toString())
                .stopReason(AgentStopReason.MODEL_ERROR)
                .code(e.getCode())
                .message(e.getMessage())
                .build();
    }

    private void fail(AgentContext context, AgentStopReason reason, String code, String message) {
        context.setStopReason(reason);
        context.setErrorCode(code);
        context.setErrorMessage(message);
    }

    private String nodeStatus(AgentContext context, NodeResult result) {
        if (StringUtils.isNotBlank(context.getErrorCode())) {
            return "failed";
        }
        if (result != null && result.isTerminal()) {
            return "terminal";
        }
        return "success";
    }

    private String stopStatus(AgentContext context) {
        if (StringUtils.isNotBlank(context.getBudgetBlockedReason())) {
            return "budget_exceeded";
        }
        return StringUtils.isBlank(context.getErrorCode()) ? "completed" : "failed";
    }

    private String stopSummary(AgentContext context) {
        if (StringUtils.isNotBlank(context.getBudgetBlockedReason())) {
            return context.getBudgetBlockedReason();
        }
        if (StringUtils.isNotBlank(context.getFinalAnswer())) {
            return StringUtils.abbreviate(context.getFinalAnswer(), 500);
        }
        return StringUtils.defaultString(context.getErrorMessage());
    }

    private String runKind(AgentContext context) {
        return StringUtils.isBlank(context.getParentRunId()) ? AgentRunKind.ROOT.name() : AgentRunKind.CHILD.name();
    }

    private void putNodeMdc(AgentContext context, String node) {
        MDC.put("trace_id", StringUtils.defaultString(context.getTraceId()));
        MDC.put("run_id", StringUtils.defaultString(context.getRunId()));
        MDC.put("request_id", StringUtils.defaultString(context.getRequestId()));
        MDC.put("conversation_id", StringUtils.defaultString(context.getConversationId()));
        MDC.put("node", StringUtils.defaultString(node));
    }

    private boolean isTotalTimeout(AgentContext context) {
        return Duration.between(context.getStartedAt(), Instant.now()).toMillis() > properties.getTotalTimeoutMs();
    }

    private void emit(FluxSink<AgentEvent> sink, List<AgentEvent> events) {
        for (AgentEvent event : events) {
            if (!sink.isCancelled()) {
                sink.next(event);
            }
        }
    }

}
