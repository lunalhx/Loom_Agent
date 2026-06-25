package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.service.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.agent.service.ContextWindowManager;
import cn.lunalhx.ai.domain.agent.service.DefaultAgentLoopService;
import cn.lunalhx.ai.domain.agent.service.DefaultBudgetGuard;
import cn.lunalhx.ai.domain.agent.service.DeepContextSummaryService;
import cn.lunalhx.ai.domain.agent.service.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.service.InMemoryAgentRunRepository;
import cn.lunalhx.ai.domain.agent.service.InMemoryApprovalStore;
import cn.lunalhx.ai.domain.agent.service.InMemoryContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.service.InMemoryContextBlobStore;
import cn.lunalhx.ai.domain.agent.service.InMemoryTraceRecorder;
import cn.lunalhx.ai.domain.agent.service.NoopAgentMetrics;
import cn.lunalhx.ai.domain.agent.service.RoleToolRegistryFactory;
import cn.lunalhx.ai.domain.agent.service.SubAgentCoordinator;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试装配入口（Phase 1 重构保护网 §2）。
 *
 * <p>集中创建 {@link DefaultAgentLoopService}、{@link SubAgentCoordinator}、
 * {@link ContextWindowManager} 以及配套的内存 Repository/Store/Trace/Metrics、
 * 同步 {@link Executor}、Fake {@link ModelGateway} 与 {@link ToolRegistry}。
 *
 * <p>这是<strong>测试代码与生产构造器之间的唯一耦合点</strong>：当 Phase 2 修改生产构造器
 * 签名时，只需调整本 Fixture，契约测试本身不动。
 *
 * <p>约束：仅存在于测试代码中；不在生产代码引入 Builder；每个测试只覆盖与场景有关的依赖；
 * 迁移前后不得修改原有断言。默认使用最全构造器（14 参数 AgentLoop / 13 参数 SubAgent），
 * 以便后续构造器裁剪时默认行为不变。
 */
public final class AgentRuntimeTestFixture {

    private static final Path DEFAULT_WORKSPACE = Path.of(".").toAbsolutePath().normalize();

    private ModelGateway modelGateway;
    private List<AgentTool> tools = new ArrayList<>();
    private AgentRuntimeProperties properties;
    private ApprovalStore approvalStore;
    private AgentRunRepository runRepository;
    private AgentCheckpointRepository checkpointRepository;
    private AgentWorkspaceResolver workspaceResolver;
    private TraceRecorder traceRecorder;
    private BudgetGuard budgetGuard;
    private AgentMetrics agentMetrics;
    private ContextArtifactRepository contextArtifactRepository;
    private ContextBlobStore contextBlobStore;
    private DeepContextSummaryService deepContextSummaryService;
    private ContextWindowManager contextWindowManager;
    private Executor executor;
    private SubAgentCoordinator subAgentCoordinator;
    private boolean subAgentEnabled = false;
    private boolean contextEnabled = false;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<ContextWindowManager> builtContextWindowManager = new AtomicReference<>();

    private AgentRuntimeTestFixture() {
    }

    public static AgentRuntimeTestFixture fixture() {
        return new AgentRuntimeTestFixture();
    }

    // ---- 显式覆盖方法（只覆盖与场景有关的依赖） ----

    public AgentRuntimeTestFixture modelGateway(ModelGateway modelGateway) {
        this.modelGateway = modelGateway;
        return this;
    }

    public AgentRuntimeTestFixture tools(List<AgentTool> tools) {
        this.tools = tools == null ? new ArrayList<>() : new ArrayList<>(tools);
        return this;
    }

    public AgentRuntimeTestFixture addTool(AgentTool tool) {
        this.tools.add(tool);
        return this;
    }

    public AgentRuntimeTestFixture properties(AgentRuntimeProperties properties) {
        this.properties = properties;
        return this;
    }

    /** 默认 properties()：与 DefaultAgentLoopServiceTest 一致的内存工作区 + 短超时。 */
    public AgentRuntimeTestFixture defaultProperties() {
        return properties(standardProperties(DEFAULT_WORKSPACE, DEFAULT_WORKSPACE));
    }

    public AgentRuntimeTestFixture approvalStore(ApprovalStore approvalStore) {
        this.approvalStore = approvalStore;
        return this;
    }

    public AgentRuntimeTestFixture runRepository(AgentRunRepository runRepository) {
        this.runRepository = runRepository;
        return this;
    }

    public AgentRuntimeTestFixture checkpointRepository(AgentCheckpointRepository checkpointRepository) {
        this.checkpointRepository = checkpointRepository;
        return this;
    }

    public AgentRuntimeTestFixture workspaceResolver(AgentWorkspaceResolver workspaceResolver) {
        this.workspaceResolver = workspaceResolver;
        return this;
    }

    public AgentRuntimeTestFixture traceRecorder(TraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
        return this;
    }

    public AgentRuntimeTestFixture budgetGuard(BudgetGuard budgetGuard) {
        this.budgetGuard = budgetGuard;
        return this;
    }

    public AgentRuntimeTestFixture agentMetrics(AgentMetrics agentMetrics) {
        this.agentMetrics = agentMetrics;
        return this;
    }

    public AgentRuntimeTestFixture contextArtifactRepository(ContextArtifactRepository contextArtifactRepository) {
        this.contextArtifactRepository = contextArtifactRepository;
        return this;
    }

    public AgentRuntimeTestFixture contextBlobStore(ContextBlobStore contextBlobStore) {
        this.contextBlobStore = contextBlobStore;
        return this;
    }

    public AgentRuntimeTestFixture deepContextSummaryService(DeepContextSummaryService deepContextSummaryService) {
        this.deepContextSummaryService = deepContextSummaryService;
        return this;
    }

    public AgentRuntimeTestFixture contextWindowManager(ContextWindowManager contextWindowManager) {
        this.contextWindowManager = contextWindowManager;
        return this;
    }

    /** 开启 ContextWindowManager 的真实功能（enabled=true），并装配内存 artifact 仓库。 */
    public AgentRuntimeTestFixture contextEnabled() {
        this.contextEnabled = true;
        return this;
    }

    public AgentRuntimeTestFixture executor(Executor executor) {
        this.executor = executor;
        return this;
    }

    public AgentRuntimeTestFixture subAgentCoordinator(SubAgentCoordinator subAgentCoordinator) {
        this.subAgentCoordinator = subAgentCoordinator;
        this.subAgentEnabled = true;
        return this;
    }

    /** 显式开启子 Agent（用默认内存依赖构建一个 coordinator，工具与主 loop 共享）。 */
    public AgentRuntimeTestFixture subAgentEnabled() {
        this.subAgentEnabled = true;
        return this;
    }

    // ---- 构建入口 ----

    /** 构建 ContextWindowManager：显式传入则用之；否则按 contextEnabled() 决定 noop 或真实功能。 */
    private ContextWindowManager resolveContextWindowManager(AgentRuntimeProperties props) {
        if (contextWindowManager != null) {
            return contextWindowManager;
        }
        if (contextEnabled) {
            ContextArtifactRepository artifactRepository = this.contextArtifactRepository != null
                    ? this.contextArtifactRepository : new InMemoryContextArtifactRepository();
            ContextBlobStore blobStore = this.contextBlobStore != null
                    ? this.contextBlobStore : new InMemoryContextBlobStore();
            ContextWindowManager manager = deepContextSummaryService != null
                    ? new ContextWindowManager(props, artifactRepository, blobStore, deepContextSummaryService)
                    : new ContextWindowManager(props, artifactRepository, blobStore);
            builtContextWindowManager.set(manager);
            return manager;
        }
        return ContextWindowManager.noop(props);
    }

    private SubAgentCoordinator resolveSubAgentCoordinator(AgentRuntimeProperties props,
                                                           ApprovalStore approvalStore,
                                                           AgentRunRepository runRepository,
                                                           AgentCheckpointRepository checkpointRepository,
                                                           AgentWorkspaceResolver workspaceResolver,
                                                           TraceRecorder traceRecorder,
                                                           BudgetGuard budgetGuard,
                                                           AgentMetrics agentMetrics,
                                                           ContextWindowManager contextWindowManager,
                                                           Executor executor) {
        if (subAgentCoordinator != null) {
            return subAgentCoordinator;
        }
        if (!subAgentEnabled) {
            return null;
        }
        // 13 参数最全构造器：Phase 2 裁剪构造器时此处集中调整。
        return new SubAgentCoordinator(
                modelGateway,
                new RoleToolRegistryFactory(tools),
                approvalStore,
                workspaceResolver,
                runRepository,
                checkpointRepository,
                props,
                objectMapper,
                executor,
                traceRecorder,
                budgetGuard,
                agentMetrics,
                contextWindowManager);
    }

    /** 构建一个 AgentLoop（最全 14 参数构造器）。 */
    public DefaultAgentLoopService buildAgentLoop() {
        AgentRuntimeProperties props = this.properties != null ? this.properties : standardProperties();
        ApprovalStore approvalStore = this.approvalStore != null ? this.approvalStore : new InMemoryApprovalStore();
        AgentRunRepository runRepository = this.runRepository != null ? this.runRepository : new InMemoryAgentRunRepository();
        AgentCheckpointRepository checkpointRepository = this.checkpointRepository != null
                ? this.checkpointRepository : new InMemoryAgentCheckpointRepository();
        AgentWorkspaceResolver workspaceResolver = this.workspaceResolver != null
                ? this.workspaceResolver : new AgentWorkspaceResolver(props);
        TraceRecorder traceRecorder = this.traceRecorder != null ? this.traceRecorder : new InMemoryTraceRecorder();
        BudgetGuard budgetGuard = this.budgetGuard != null ? this.budgetGuard : new DefaultBudgetGuard(props);
        AgentMetrics agentMetrics = this.agentMetrics != null ? this.agentMetrics : new NoopAgentMetrics();
        Executor executor = this.executor != null ? this.executor : Runnable::run;
        ContextWindowManager contextWindowManager = resolveContextWindowManager(props);
        SubAgentCoordinator subAgentCoordinator = resolveSubAgentCoordinator(props, approvalStore, runRepository,
                checkpointRepository, workspaceResolver, traceRecorder, budgetGuard, agentMetrics,
                contextWindowManager, executor);
        // 14 参数最全构造器：Phase 2 裁剪构造器时此处集中调整。
        return new DefaultAgentLoopService(
                modelGateway,
                new ToolRegistry(tools),
                approvalStore,
                workspaceResolver,
                runRepository,
                checkpointRepository,
                props,
                objectMapper,
                executor,
                subAgentCoordinator,
                traceRecorder,
                budgetGuard,
                agentMetrics,
                contextWindowManager);
    }

    /** 构建一个独立的 SubAgentCoordinator（13 参数最全构造器），不构建 AgentLoop。 */
    public SubAgentCoordinator buildSubAgentCoordinator() {
        AgentRuntimeProperties props = this.properties != null ? this.properties : standardProperties();
        ApprovalStore approvalStore = this.approvalStore != null ? this.approvalStore : new InMemoryApprovalStore();
        AgentRunRepository runRepository = this.runRepository != null ? this.runRepository : new InMemoryAgentRunRepository();
        AgentCheckpointRepository checkpointRepository = this.checkpointRepository != null
                ? this.checkpointRepository : new InMemoryAgentCheckpointRepository();
        AgentWorkspaceResolver workspaceResolver = this.workspaceResolver != null
                ? this.workspaceResolver : new AgentWorkspaceResolver(props);
        TraceRecorder traceRecorder = this.traceRecorder != null ? this.traceRecorder : new InMemoryTraceRecorder();
        BudgetGuard budgetGuard = this.budgetGuard != null ? this.budgetGuard : new DefaultBudgetGuard(props);
        AgentMetrics agentMetrics = this.agentMetrics != null ? this.agentMetrics : new NoopAgentMetrics();
        Executor executor = this.executor != null ? this.executor : Runnable::run;
        ContextWindowManager contextWindowManager = resolveContextWindowManager(props);
        SubAgentCoordinator existing = resolveSubAgentCoordinator(props, approvalStore, runRepository,
                checkpointRepository, workspaceResolver, traceRecorder, budgetGuard, agentMetrics,
                contextWindowManager, executor);
        if (existing != null) {
            return existing;
        }
        return new SubAgentCoordinator(
                modelGateway,
                new RoleToolRegistryFactory(tools),
                approvalStore,
                workspaceResolver,
                runRepository,
                checkpointRepository,
                props,
                objectMapper,
                executor,
                traceRecorder,
                budgetGuard,
                agentMetrics,
                contextWindowManager);
    }

    /** 构建一个真实功能的 ContextWindowManager（4 参数最全构造器，含 deepSummaryService）。 */
    public ContextWindowManager buildContextWindowManager() {
        AgentRuntimeProperties props = this.properties != null ? this.properties : standardProperties();
        ContextWindowManager resolved = resolveContextWindowManager(props);
        builtContextWindowManager.set(resolved);
        return resolved;
    }

    // ---- 访问器：测试断言副作用时使用 ----

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public ContextArtifactRepository contextArtifactRepository() {
        return contextArtifactRepository;
    }

    public ContextBlobStore contextBlobStore() {
        return contextBlobStore;
    }

    public AgentRuntimeProperties properties() {
        return properties;
    }

    /** 获取 buildAgentLoop() 内部装配的 ContextWindowManager（可能为 noop）。 */
    public ContextWindowManager builtContextWindowManager() {
        return builtContextWindowManager.get();
    }

    // ---- 默认 properties 工厂（与 DefaultAgentLoopServiceTest 对齐） ----

    public static AgentRuntimeProperties standardProperties() {
        return standardProperties(DEFAULT_WORKSPACE, DEFAULT_WORKSPACE);
    }

    public static AgentRuntimeProperties standardProperties(Path workspaceRoot, Path allowedRoot) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setWorkspaceRoot(workspaceRoot.toString());
        properties.setAllowedWorkspaceRoots(List.of(allowedRoot.toString()));
        properties.setStepTimeoutMs(1000L);
        properties.setTotalTimeoutMs(3000L);
        properties.setToolTimeoutMs(1000L);
        properties.setObservationMaxChars(8000);
        properties.setMaxSteps(6);
        return properties;
    }
}
