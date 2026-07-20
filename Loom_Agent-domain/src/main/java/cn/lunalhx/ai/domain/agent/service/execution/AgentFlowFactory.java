package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.SkillRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookRegistry;
import cn.lunalhx.ai.domain.agent.flow.middleware.BudgetMiddleware;
import cn.lunalhx.ai.domain.agent.flow.middleware.DynamicContextMiddleware;
import cn.lunalhx.ai.domain.agent.flow.middleware.ErrorRecoveryMiddleware;
import cn.lunalhx.ai.domain.agent.flow.middleware.ModelCallMiddlewareAssembler;
import cn.lunalhx.ai.domain.agent.flow.middleware.SummarizationMiddleware;
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
import cn.lunalhx.ai.domain.agent.flow.node.PlannerNode;
import cn.lunalhx.ai.domain.agent.flow.node.ReplanGuardNode;
import cn.lunalhx.ai.domain.agent.flow.node.ReplanNode;
import cn.lunalhx.ai.domain.agent.flow.node.MemoryRecallNode;
import cn.lunalhx.ai.domain.agent.flow.node.SkillBootstrapNode;
import cn.lunalhx.ai.domain.agent.flow.node.StartNode;
import cn.lunalhx.ai.domain.agent.flow.node.SubAgentDispatchNode;
import cn.lunalhx.ai.domain.agent.flow.node.ToolDispatchNode;
import cn.lunalhx.ai.domain.agent.flow.node.UserInputGateNode;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.agent.service.subagent.SubAgentCoordinator;
import cn.lunalhx.ai.domain.agent.service.context.ContextWindowManager;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerBootstrapService;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerCompactionService;
import cn.lunalhx.ai.domain.agent.service.prompt.LedgerPromptServices;
import cn.lunalhx.ai.domain.agent.service.prompt.RenderPromptResources;
import cn.lunalhx.ai.domain.agent.service.prompt.StablePrefixBuilder;
import cn.lunalhx.ai.domain.agent.service.undo.UndoSessionCoordinator;
import cn.lunalhx.ai.domain.memory.service.MemorySelectionService;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
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
 * Hook 通过 {@link AgentHookRegistry} 注入，不再直接依赖具体 Hook 实现。
 */
public class AgentFlowFactory {

    private final ModelGateway modelGateway;
    private final AgentLoopStateDependencies state;
    private final AgentLoopRuntimeDependencies runtime;
    private final ObjectMapper objectMapper;
    private final AgentHookRegistry hookRegistry;
    private final UndoSessionCoordinator undoCoordinator;
    private final SkillRepository skillRepository;
    private final ContextArtifactRepository contextArtifactRepository;
    private final ContextBlobStore contextBlobStore;
    private final ConversationLedgerAppendService ledgerAppendService;
    private final LedgerBootstrapService bootstrapService;
    private final LedgerCompactionService ledgerCompactionService;
    private final MemorySelectionService memorySelectionService;

    public AgentFlowFactory(ModelGateway modelGateway,
                           AgentLoopStateDependencies state,
                           AgentLoopRuntimeDependencies runtime,
                           AgentHookRegistry hookRegistry,
                           UndoSessionCoordinator undoCoordinator,
                           SkillRepository skillRepository,
                           ContextArtifactRepository contextArtifactRepository,
                           ContextBlobStore contextBlobStore,
                           ConversationLedgerAppendService ledgerAppendService,
                           LedgerBootstrapService bootstrapService,
                           LedgerCompactionService ledgerCompactionService,
                           MemorySelectionService memorySelectionService) {
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.objectMapper = state.objectMapper();
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry must not be null");
        this.undoCoordinator = undoCoordinator;
        this.skillRepository = skillRepository;
        this.contextArtifactRepository = contextArtifactRepository;
        this.contextBlobStore = contextBlobStore;
        this.ledgerAppendService = Objects.requireNonNull(ledgerAppendService, "ledgerAppendService must not be null");
        this.bootstrapService = Objects.requireNonNull(bootstrapService, "bootstrapService must not be null");
        this.ledgerCompactionService = Objects.requireNonNull(
                ledgerCompactionService, "ledgerCompactionService must not be null");
        this.memorySelectionService = memorySelectionService;
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

        // ---- Phase 2: Middleware chain ----
        SummarizationMiddleware summarizationMiddleware =
                new SummarizationMiddleware(ledgerCompactionService);
        DynamicContextMiddleware dynamicContextMiddleware =
                new DynamicContextMiddleware(ledgerAppendService);
        cn.lunalhx.ai.domain.agent.flow.node.ModelPromptFactory middlewarePromptFactory =
                new cn.lunalhx.ai.domain.agent.flow.node.ModelPromptFactory();
        BudgetMiddleware budgetMiddleware =
                new BudgetMiddleware(budgetGuard, properties, middlewarePromptFactory::budgetInput);
        ErrorRecoveryMiddleware errorRecoveryMiddleware =
                new ErrorRecoveryMiddleware(
                        RecoveryChainFactory.createRecoveryChain(
                                properties, modelGateway,
                                new cn.lunalhx.ai.domain.agent.flow.node.ModelPromptFactory(),
                                budgetGuard, traceRecorder, ledgerCompactionService),
                        RecoveryChainFactory.createModelErrorRecoveryChain(
                                ledgerAppendService,
                                runtime.modelRuntimeProperties(),
                                ledgerCompactionService),
                        new ModelCallFailureClassifier(),
                        properties,
                        traceRecorder);
        ModelCallMiddlewareAssembler assembler =
                new ModelCallMiddlewareAssembler(
                        summarizationMiddleware,
                        dynamicContextMiddleware,
                        errorRecoveryMiddleware,
                        budgetMiddleware);

        List<AgentNode> nodeList = new ArrayList<>(List.of(
                new SkillBootstrapNode(skillRepository, state.approvalStore(),
                        contextArtifactRepository, contextBlobStore, properties),
                new MemoryRecallNode(memorySelectionService),
                new StartNode(),
                new PlannerNode(),
                new RenderPromptNode(contextWindowManager,
                        new RenderPromptResources(skillRepository,
                                contextArtifactRepository, contextBlobStore),
                        new LedgerPromptServices(bootstrapService,
                                new StablePrefixBuilder(),
                                ledgerCompactionService)),
                new ModelCallNode(assembler, properties, ledgerAppendService,
                        new cn.lunalhx.ai.domain.agent.flow.node.ModelCallTerminalDeps(
                                modelGateway, budgetGuard, traceRecorder)),
                new DecisionNode(objectMapper, toolRegistry, properties, ledgerAppendService),
                new InstructionGateNode(),
                new ApprovalGateNode(toolRegistry, state.approvalStore(), properties),
                new ToolDispatchNode(toolRegistry, properties, hookRegistry, contextWindowManager, ledgerAppendService),
                new ObservationNode(runtime.toolOutputSanitizer(), traceRecorder,
                        runtime.agentMetrics(), ledgerAppendService),
                new ReplanGuardNode(new ProgressGuard(properties, ledgerAppendService)),
                new ReplanNode(modelGateway, properties, objectMapper, traceRecorder, budgetGuard, ledgerAppendService),
                new FinalAnswerNode(ledgerAppendService),
                new UserInputGateNode(),
                new FailNode()));
        if (subAgentAvailable) {
            nodeList.add(new SubAgentDispatchNode(subAgentCoordinator, properties, ledgerAppendService));
        }

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

        return new AgentFlowDefinition(
                nodes,
                hookRegistry,
                toolRegistry.specs(),
                subAgentAvailable);
    }
}
