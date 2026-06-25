package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookRegistry;
import cn.lunalhx.ai.domain.agent.flow.hook.CheckpointAgentHook;
import cn.lunalhx.ai.domain.agent.flow.node.ApprovalGateNode;
import cn.lunalhx.ai.domain.agent.flow.node.DecisionNode;
import cn.lunalhx.ai.domain.agent.flow.node.FailNode;
import cn.lunalhx.ai.domain.agent.flow.node.FinalAnswerNode;
import cn.lunalhx.ai.domain.agent.flow.node.ModelCallNode;
import cn.lunalhx.ai.domain.agent.flow.node.ObservationNode;
import cn.lunalhx.ai.domain.agent.flow.node.PlannerNode;
import cn.lunalhx.ai.domain.agent.flow.node.RenderPromptNode;
import cn.lunalhx.ai.domain.agent.flow.node.ReplanGuardNode;
import cn.lunalhx.ai.domain.agent.flow.node.ReplanNode;
import cn.lunalhx.ai.domain.agent.flow.node.StartNode;
import cn.lunalhx.ai.domain.agent.flow.node.SubAgentDispatchNode;
import cn.lunalhx.ai.domain.agent.flow.node.ToolDispatchNode;
import cn.lunalhx.ai.domain.agent.flow.node.UserInputGateNode;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 节点图工厂（Phase 2 §二）。
 *
 * <p>集中创建 Agent Loop 的节点图（{@link AgentFlowDefinition}），将节点创建逻辑
 * 从 {@link DefaultAgentLoopService} 构造器中移出。
 *
 * <p>Factory 内部不创建 InMemory、Noop 或其他默认依赖，所有依赖必须显式传入。
 */
public class AgentFlowFactory {

    private final ModelGateway modelGateway;
    private final AgentLoopStateDependencies state;
    private final AgentLoopRuntimeDependencies runtime;
    private final ObjectMapper objectMapper;

    public AgentFlowFactory(ModelGateway modelGateway,
                           AgentLoopStateDependencies state,
                           AgentLoopRuntimeDependencies runtime) {
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.objectMapper = state.objectMapper();
    }

    /**
     * 创建独立 Agent 节点图（13 个基础节点，无子 Agent 能力）。
     */
    public AgentFlowDefinition createStandalone(ToolRegistry toolRegistry) {
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        return create(toolRegistry, false, null);
    }

    /**
     * 创建根 Agent 节点图（13 个基础节点 + SubAgentDispatchNode）。
     *
     * @param subAgentCoordinator 必须非 null
     */
    public AgentFlowDefinition createRoot(ToolRegistry toolRegistry, SubAgentCoordinator subAgentCoordinator) {
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        Objects.requireNonNull(subAgentCoordinator, "subAgentCoordinator must not be null");
        return create(toolRegistry, true, subAgentCoordinator);
    }

    private AgentFlowDefinition create(ToolRegistry toolRegistry,
                                       boolean subAgentAvailable,
                                       SubAgentCoordinator subAgentCoordinator) {
        AgentRuntimeProperties properties = runtime.properties();
        TraceRecorder traceRecorder = runtime.traceRecorder();
        BudgetGuard budgetGuard = runtime.budgetGuard();
        ContextWindowManager contextWindowManager = runtime.contextWindowManager();

        List<AgentNode> nodeList = new ArrayList<>(List.of(
                new StartNode(),
                new PlannerNode(),
                new RenderPromptNode(contextWindowManager),
                new ModelCallNode(modelGateway, properties, traceRecorder, budgetGuard, contextWindowManager),
                new DecisionNode(objectMapper, toolRegistry, properties),
                new ApprovalGateNode(toolRegistry, state.approvalStore(), properties),
                new ToolDispatchNode(toolRegistry, properties, hookRegistry(), contextWindowManager),
                new ObservationNode(),
                new ReplanGuardNode(),
                new ReplanNode(modelGateway, properties, objectMapper, traceRecorder, budgetGuard),
                new FinalAnswerNode(),
                new UserInputGateNode(),
                new FailNode()));
        if (subAgentAvailable) {
            nodeList.add(new SubAgentDispatchNode(subAgentCoordinator, properties));
        }

        Map<String, AgentNode> nodes = new LinkedHashMap<>();
        for (AgentNode node : nodeList) {
            if (nodes.containsKey(node.name())) {
                throw new IllegalStateException("重复的 Agent 节点：" + node.name());
            }
            nodes.put(node.name(), node);
        }
        // 校验必须包含 START 和 FAIL
        if (!nodes.containsKey(AgentNodeNames.START)) {
            throw new IllegalStateException("Agent 节点图必须包含 START 节点");
        }
        if (!nodes.containsKey(AgentNodeNames.FAIL)) {
            throw new IllegalStateException("Agent 节点图必须包含 FAIL 节点");
        }

        return new AgentFlowDefinition(
                nodes,
                hookRegistry(),
                toolRegistry.specs(),
                subAgentAvailable);
    }

    private AgentHookRegistry hookRegistry() {
        return new AgentHookRegistry(List.of(
                new CheckpointAgentHook(state.runRepository(), state.checkpointRepository(), objectMapper)));
    }
}
