package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.middleware.BudgetMiddleware;
import cn.lunalhx.ai.domain.agent.flow.middleware.ErrorRecoveryMiddleware;
import cn.lunalhx.ai.domain.agent.flow.middleware.ModelCallMiddlewareAssembler;
import cn.lunalhx.ai.domain.agent.flow.node.ModelCallFailureClassifier;
import cn.lunalhx.ai.domain.agent.flow.node.ModelCallNode;
import cn.lunalhx.ai.domain.agent.flow.node.RecoveryChainFactory;
import cn.lunalhx.ai.domain.agent.flow.node.DecisionNode;
import cn.lunalhx.ai.domain.agent.flow.node.ObservationNode;
import cn.lunalhx.ai.domain.agent.flow.node.PromptBuildNode;
import cn.lunalhx.ai.domain.agent.flow.node.ToolDispatchNode;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerBootstrapService;
import cn.lunalhx.ai.domain.agent.service.prompt.LedgerPromptServices;
import cn.lunalhx.ai.domain.agent.service.prompt.StablePrefixBuilder;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.service.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Node-graph factory for the loom-code six-node agent loop.
 */
public class AgentFlowFactory {

    private final ModelGateway modelGateway;
    private final AgentLoopStateDependencies state;
    private final AgentLoopRuntimeDependencies runtime;
    private final ObjectMapper objectMapper;
    private final ConversationHistoryAppendService ledgerAppendService;
    private final LedgerBootstrapService bootstrapService;
    private final ContextManager contextManager;
    private final ToolExecutor.ApprovalPrompt approvalPrompt;

    public AgentFlowFactory(ModelGateway modelGateway,
                            AgentLoopStateDependencies state,
                            AgentLoopRuntimeDependencies runtime,
                            ConversationHistoryAppendService ledgerAppendService,
                            LedgerBootstrapService bootstrapService,
                            ContextManager contextManager) {
        this(modelGateway, state, runtime, ledgerAppendService, bootstrapService, contextManager, null);
    }

    public AgentFlowFactory(ModelGateway modelGateway,
                            AgentLoopStateDependencies state,
                            AgentLoopRuntimeDependencies runtime,
                            ConversationHistoryAppendService ledgerAppendService,
                            LedgerBootstrapService bootstrapService,
                            ContextManager contextManager,
                            ToolExecutor.ApprovalPrompt approvalPrompt) {
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.objectMapper = state.objectMapper();
        this.ledgerAppendService = Objects.requireNonNull(ledgerAppendService, "ledgerAppendService must not be null");
        this.bootstrapService = Objects.requireNonNull(bootstrapService, "bootstrapService must not be null");
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager must not be null");
        this.approvalPrompt = approvalPrompt;
    }

    public AgentFlowDefinition create(ToolRegistry toolRegistry) {
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        AgentRuntimeProperties properties = runtime.properties();
        TraceRecorder traceRecorder = runtime.traceRecorder();
        BudgetGuard budgetGuard = runtime.budgetGuard();

        ErrorRecoveryMiddleware errorRecoveryMiddleware =
                new ErrorRecoveryMiddleware(
                        RecoveryChainFactory.createRecoveryChain(
                                properties, budgetGuard, traceRecorder, contextManager),
                        RecoveryChainFactory.createModelErrorRecoveryChain(
                                ledgerAppendService,
                                runtime.modelRuntimeProperties()),
                        new ModelCallFailureClassifier(),
                        properties,
                        traceRecorder);
        BudgetMiddleware budgetMiddleware =
                new BudgetMiddleware(budgetGuard, properties);
        ModelCallMiddlewareAssembler assembler =
                new ModelCallMiddlewareAssembler(errorRecoveryMiddleware, budgetMiddleware);

        List<AgentNode> nodeList = new ArrayList<>(List.of(
                new PromptBuildNode(
                        new LedgerPromptServices(bootstrapService, new StablePrefixBuilder()),
                        contextManager, ledgerAppendService),
                new ModelCallNode(assembler, properties, ledgerAppendService,
                        new cn.lunalhx.ai.domain.agent.flow.node.ModelCallTerminalDeps(
                                modelGateway, budgetGuard, traceRecorder, runtime.agentMetrics())),
                new DecisionNode(objectMapper, toolRegistry, properties, ledgerAppendService),
                new ToolDispatchNode(new ToolExecutor(toolRegistry, approvalPrompt), properties),
                new ObservationNode(runtime.toolOutputSanitizer(), traceRecorder,
                        runtime.agentMetrics(), ledgerAppendService)
        ));

        Map<String, AgentNode> nodes = new LinkedHashMap<>();
        for (AgentNode node : nodeList) {
            if (nodes.containsKey(node.name())) {
                throw new IllegalStateException("重复的 Agent 节点：" + node.name());
            }
            nodes.put(node.name(), node);
        }
        for (String name : AgentNodeNames.ALL) {
            if (!nodes.containsKey(name)) {
                throw new IllegalStateException("Agent 节点图必须包含 " + name + " 节点");
            }
        }

        return new AgentFlowDefinition(nodes, toolRegistry.specs());
    }
}