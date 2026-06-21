package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.flow.node.ApprovalGateNode;
import cn.lunalhx.ai.domain.agent.flow.node.FailNode;
import cn.lunalhx.ai.domain.agent.flow.node.FinalAnswerNode;
import cn.lunalhx.ai.domain.agent.flow.node.ModelCallNode;
import cn.lunalhx.ai.domain.agent.flow.node.ObservationNode;
import cn.lunalhx.ai.domain.agent.flow.node.DecisionNode;
import cn.lunalhx.ai.domain.agent.flow.node.RenderPromptNode;
import cn.lunalhx.ai.domain.agent.flow.node.StartNode;
import cn.lunalhx.ai.domain.agent.flow.node.ToolDispatchNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentWorkspace;
import cn.lunalhx.ai.domain.agent.model.valobj.WorkspaceResolutionException;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

public class DefaultAgentLoopService implements AgentLoopService {

    private final AgentRuntimeProperties properties;
    private final ApprovalStore approvalStore;
    private final AgentWorkspaceResolver workspaceResolver;
    private final Executor executor;
    private final List<ToolSpec> toolSpecs;
    private final Map<String, AgentNode> nodes;

    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor) {
        this(modelGateway, toolRegistry, new InMemoryApprovalStore(), new AgentWorkspaceResolver(properties), properties, objectMapper, executor);
    }

    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   ApprovalStore approvalStore,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor) {
        this(modelGateway, toolRegistry, approvalStore, new AgentWorkspaceResolver(properties), properties, objectMapper, executor);
    }

    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   ApprovalStore approvalStore,
                                   AgentWorkspaceResolver workspaceResolver,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor) {
        this.properties = properties;
        this.approvalStore = approvalStore;
        this.workspaceResolver = workspaceResolver;
        this.executor = executor;
        this.toolSpecs = toolRegistry.specs();
        this.nodes = registerNodes(List.of(
                new StartNode(),
                new RenderPromptNode(),
                new ModelCallNode(modelGateway, properties),
                new DecisionNode(objectMapper, toolRegistry, properties),
                new ApprovalGateNode(toolRegistry, approvalStore, properties),
                new ToolDispatchNode(toolRegistry, properties),
                new ObservationNode(),
                new FinalAnswerNode(),
                new FailNode()));
    }

    @Override
    public Flux<AgentEvent> ask(AgentQuestion question) {
        return Flux.create(sink -> executor.execute(() -> {
            try {
                runLoop(toContext(question), AgentNodeNames.START, sink);
            } catch (WorkspaceResolutionException e) {
                emit(sink, List.of(workspaceError(e)));
                sink.complete();
            } catch (Exception e) {
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
        context.setResolvedWorkspace(approval.getResolvedWorkspace());
        context.setWorkspaceDisplayName(approval.getWorkspaceDisplayName());
        context.setStartedAt(Instant.now());
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

    private void runLoop(AgentContext context, String currentNode, FluxSink<AgentEvent> sink) {
        while (!sink.isCancelled()) {
            if (isTotalTimeout(context)) {
                fail(context, AgentStopReason.TIMEOUT, "agent_timeout", "Agent 执行超时");
                currentNode = AgentNodeNames.FAIL;
            }

            AgentNode node = nodes.get(currentNode);
            if (node == null) {
                fail(context, AgentStopReason.MODEL_ERROR, "node_not_found", "未知节点：" + currentNode);
                node = nodes.get(AgentNodeNames.FAIL);
            }

            emit(sink, List.of(nodeStartEvent(context, node)));
            NodeResult result = node.apply(context);
            emit(sink, result.getEvents());
            if (result.isTerminal()) {
                sink.complete();
                return;
            }
            currentNode = result.getNextNode();
        }
    }

    private AgentContext toContext(AgentQuestion question) {
        AgentWorkspace workspace = workspaceResolver.resolve(question.getWorkspace());
        AgentContext context = new AgentContext();
        context.setRequestId(StringUtils.defaultIfBlank(question.getRequestId(), UUID.randomUUID().toString()));
        context.setConversationId(StringUtils.defaultIfBlank(question.getConversationId(), UUID.randomUUID().toString()));
        context.setQuestion(StringUtils.trim(question.getQuestion()));
        context.setResolvedWorkspace(workspace.getRoot());
        context.setWorkspaceDisplayName(workspace.getDisplayName());
        context.setMaxSteps(question.getMaxSteps() == null ? properties.getMaxSteps() : question.getMaxSteps());
        context.setStartedAt(Instant.now());
        context.setToolSpecs(toolSpecs);
        context.getDynamicText().appendUserTask(context.getQuestion());
        return context;
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
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .node(node.name())
                .nodeInputs(node.inputKeys())
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
