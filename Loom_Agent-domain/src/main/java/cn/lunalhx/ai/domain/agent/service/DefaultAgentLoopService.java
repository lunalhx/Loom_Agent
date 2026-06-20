package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.flow.node.FailNode;
import cn.lunalhx.ai.domain.agent.flow.node.FinalAnswerNode;
import cn.lunalhx.ai.domain.agent.flow.node.ModelDecisionNode;
import cn.lunalhx.ai.domain.agent.flow.node.ObservationNode;
import cn.lunalhx.ai.domain.agent.flow.node.ParseDecisionNode;
import cn.lunalhx.ai.domain.agent.flow.node.RenderPromptNode;
import cn.lunalhx.ai.domain.agent.flow.node.StartNode;
import cn.lunalhx.ai.domain.agent.flow.node.ToolDispatchNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
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
    private final Executor executor;
    private final List<ToolSpec> toolSpecs;
    private final Map<String, AgentNode> nodes;

    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor) {
        this.properties = properties;
        this.executor = executor;
        this.toolSpecs = toolRegistry.specs();
        this.nodes = registerNodes(List.of(
                new StartNode(),
                new RenderPromptNode(),
                new ModelDecisionNode(modelGateway, properties),
                new ParseDecisionNode(objectMapper, toolRegistry, properties),
                new ToolDispatchNode(toolRegistry, properties),
                new ObservationNode(),
                new FinalAnswerNode(),
                new FailNode()));
    }

    @Override
    public Flux<AgentEvent> ask(AgentQuestion question) {
        return Flux.create(sink -> executor.execute(() -> run(question, sink)), FluxSink.OverflowStrategy.BUFFER);
    }

    private void run(AgentQuestion question, FluxSink<AgentEvent> sink) {
        AgentContext context = toContext(question);
        String currentNode = AgentNodeNames.START;
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
            NodeResult result = node.execute(context);
            emit(sink, result.getEvents());
            if (result.isTerminal()) {
                sink.complete();
                return;
            }
            currentNode = result.getNextNode();
        }
    }

    private AgentContext toContext(AgentQuestion question) {
        AgentContext context = new AgentContext();
        context.setRequestId(StringUtils.defaultIfBlank(question.getRequestId(), UUID.randomUUID().toString()));
        context.setConversationId(StringUtils.defaultIfBlank(question.getConversationId(), UUID.randomUUID().toString()));
        context.setQuestion(StringUtils.trim(question.getQuestion()));
        context.setMaxSteps(question.getMaxSteps() == null ? properties.getMaxSteps() : question.getMaxSteps());
        context.setStartedAt(Instant.now());
        context.setToolSpecs(toolSpecs);
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
                .node(node.name())
                .nodeInputs(node.inputKeys())
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
