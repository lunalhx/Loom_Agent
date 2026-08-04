package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookRegistry;
import cn.lunalhx.ai.domain.agent.flow.middleware.BudgetMiddleware;
import cn.lunalhx.ai.domain.agent.flow.middleware.ContextReductionMiddleware;
import cn.lunalhx.ai.domain.agent.flow.middleware.DynamicContextMiddleware;
import cn.lunalhx.ai.domain.agent.flow.middleware.ErrorRecoveryMiddleware;
import cn.lunalhx.ai.domain.agent.flow.middleware.ModelCallMiddlewareAssembler;
import cn.lunalhx.ai.domain.agent.flow.node.ModelCallFailureClassifier;
import cn.lunalhx.ai.domain.agent.flow.node.ModelCallNode;
import cn.lunalhx.ai.domain.agent.flow.node.RecoveryChainFactory;
import cn.lunalhx.ai.domain.agent.flow.node.RenderPromptNode;
import cn.lunalhx.ai.domain.agent.flow.node.ApprovalGateNode;
import cn.lunalhx.ai.domain.agent.flow.node.DecisionNode;
import cn.lunalhx.ai.domain.agent.flow.node.FailNode;
import cn.lunalhx.ai.domain.agent.flow.node.FinalAnswerNode;
import cn.lunalhx.ai.domain.agent.flow.node.InstructionGateNode;
import cn.lunalhx.ai.domain.agent.flow.node.ObservationNode;
import cn.lunalhx.ai.domain.agent.flow.node.StartNode;
import cn.lunalhx.ai.domain.agent.flow.node.ToolDispatchNode;
import cn.lunalhx.ai.domain.agent.flow.node.UserInputGateNode;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerBootstrapService;
import cn.lunalhx.ai.domain.agent.service.prompt.LedgerPromptServices;
import cn.lunalhx.ai.domain.agent.service.prompt.RenderPromptResources;
import cn.lunalhx.ai.domain.agent.service.prompt.StablePrefixBuilder;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Node-graph factory for the loom-code 7-tool agent loop.
 */
public class AgentFlowFactory {

    private final ModelGateway modelGateway;
    private final AgentLoopStateDependencies state;
    private final AgentLoopRuntimeDependencies runtime;
    private final ObjectMapper objectMapper;
    private final AgentHookRegistry hookRegistry;
    private final ConversationHistoryAppendService ledgerAppendService;
    private final LedgerBootstrapService bootstrapService;
    private final ContextManager contextManager;

    public AgentFlowFactory(ModelGateway modelGateway,
                            AgentLoopStateDependencies state,
                            AgentLoopRuntimeDependencies runtime,
                            AgentHookRegistry hookRegistry,
                            ConversationHistoryAppendService ledgerAppendService,
                            LedgerBootstrapService bootstrapService,
                            ContextManager contextManager) {
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.objectMapper = state.objectMapper();
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry must not be null");
        this.ledgerAppendService = Objects.requireNonNull(ledgerAppendService, "ledgerAppendService must not be null");
        this.bootstrapService = Objects.requireNonNull(bootstrapService, "bootstrapService must not be null");
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager must not be null");
    }

    public AgentFlowDefinition create(ToolRegistry toolRegistry) {
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        AgentRuntimeProperties properties = runtime.properties();
        TraceRecorder traceRecorder = runtime.traceRecorder();
        BudgetGuard budgetGuard = runtime.budgetGuard();

        ContextReductionMiddleware contextReductionMiddleware =
                new ContextReductionMiddleware(contextManager, ledgerAppendService, properties);
        DynamicContextMiddleware dynamicContextMiddleware =
                new DynamicContextMiddleware(ledgerAppendService);
        BudgetMiddleware budgetMiddleware =
                new BudgetMiddleware(budgetGuard, properties);
        ErrorRecoveryMiddleware errorRecoveryMiddleware =
                new ErrorRecoveryMiddleware(
                        RecoveryChainFactory.createRecoveryChain(
                                properties, modelGateway,
                                budgetGuard, traceRecorder, contextManager),
                        RecoveryChainFactory.createModelErrorRecoveryChain(
                                ledgerAppendService,
                                runtime.modelRuntimeProperties()),
                        new ModelCallFailureClassifier(),
                        properties,
                        traceRecorder);
        ModelCallMiddlewareAssembler assembler =
                new ModelCallMiddlewareAssembler(
                        contextReductionMiddleware,
                        dynamicContextMiddleware,
                        errorRecoveryMiddleware,
                        budgetMiddleware);

        List<AgentNode> nodeList = new ArrayList<>(List.of(
                new StartNode(),
                new RenderPromptNode(RenderPromptResources.empty(),
                        new LedgerPromptServices(bootstrapService, new StablePrefixBuilder())),
                new ModelCallNode(assembler, properties, ledgerAppendService,
                        new cn.lunalhx.ai.domain.agent.flow.node.ModelCallTerminalDeps(
                                modelGateway, budgetGuard, traceRecorder)),
                new DecisionNode(objectMapper, toolRegistry, properties, ledgerAppendService),
                new InstructionGateNode(),
                new ApprovalGateNode(toolRegistry, state.approvalStore(), properties),
                new ToolDispatchNode(toolRegistry, properties, hookRegistry, ledgerAppendService),
                new ObservationNode(runtime.toolOutputSanitizer(), traceRecorder,
                        runtime.agentMetrics(), ledgerAppendService),
                new FinalAnswerNode(ledgerAppendService),
                new UserInputGateNode(),
                new FailNode()));

        Map<String, AgentNode> nodes = new LinkedHashMap<>();
        for (AgentNode node : nodeList) {
            if (nodes.containsKey(node.name())) {
                throw new IllegalStateException("重复的 Agent 节点：" + node.name());
            }
            nodes.put(node.name(), node);
        }
        if (!nodes.containsKey(AgentNodeNames.START)) {
            throw new IllegalStateException("Agent 节点图必须包含 START 节点");
        }
        if (!nodes.containsKey(AgentNodeNames.FAIL)) {
            throw new IllegalStateException("Agent 节点图必须包含 FAIL 节点");
        }

        return new AgentFlowDefinition(nodes, hookRegistry, toolRegistry.specs());
    }
}
