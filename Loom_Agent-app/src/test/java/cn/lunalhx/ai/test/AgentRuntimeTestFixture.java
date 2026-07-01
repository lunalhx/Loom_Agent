package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.service.AgentLoopFactory;
import cn.lunalhx.ai.domain.agent.service.AgentLoopRuntimeDependencies;
import cn.lunalhx.ai.domain.agent.service.AgentLoopStateDependencies;
import cn.lunalhx.ai.domain.agent.service.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.agent.service.ContextWindowManager;
import cn.lunalhx.ai.domain.agent.service.DefaultAgentLoopService;
import cn.lunalhx.ai.domain.agent.service.DefaultBudgetGuard;
import cn.lunalhx.ai.domain.agent.service.DeepContextSummaryService;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryApprovalStore;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryContextArtifactRepository;
import cn.lunalhx.ai.infrastructure.context.InMemoryContextBlobStore;
import cn.lunalhx.ai.domain.agent.adapter.port.SubAgentControlInbox;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookRegistry;
import cn.lunalhx.ai.infrastructure.adapter.port.InMemorySubAgentControlInbox;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder;
import cn.lunalhx.ai.runtime.hook.CheckpointAgentHook;
import cn.lunalhx.ai.runtime.hook.IncompletePlanStopHook;
import cn.lunalhx.ai.runtime.hook.MaxStepContinuationStopHook;
import cn.lunalhx.ai.runtime.hook.PendingApprovalConsistencyStopHook;
import cn.lunalhx.ai.runtime.hook.SubAgentGracefulStopHook;
import cn.lunalhx.ai.runtime.hook.SubAgentPartialSummaryGenerator;
import cn.lunalhx.ai.domain.agent.service.NoopAgentMetrics;
import cn.lunalhx.ai.domain.agent.service.RoleToolRegistryFactory;
import cn.lunalhx.ai.domain.agent.service.SubAgentCoordinator;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import cn.lunalhx.ai.infrastructure.tool.RegexToolOutputSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试装配入口。
 *
 * <p>集中创建 {@link DefaultAgentLoopService}、{@link SubAgentCoordinator}、
 * {@link ContextWindowManager} 以及配套的内存 Repository/Store/Trace/Metrics、
 * 同步 {@link Executor}、Fake {@link ModelGateway} 与 {@link ToolRegistry}。
 *
 * <p>生产构造器只通过 {@link AgentLoopFactory} 和 {@link SubAgentCoordinator}
 * 的 5 参数构造器创建实例，不再使用废弃构造路径。
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
    private ToolOutputSanitizer toolOutputSanitizer;
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

    // ---- 显式覆盖方法 ----

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

    public AgentRuntimeTestFixture toolOutputSanitizer(ToolOutputSanitizer toolOutputSanitizer) {
        this.toolOutputSanitizer = toolOutputSanitizer;
        return this;
    }

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

    public AgentRuntimeTestFixture subAgentEnabled() {
        this.subAgentEnabled = true;
        return this;
    }

    // ---- 内部装配 ----

    private AgentRuntimeProperties props() {
        return properties != null ? properties : standardProperties();
    }

    private ApprovalStore effectiveApprovalStore() {
        return approvalStore != null ? approvalStore : new InMemoryApprovalStore();
    }

    private AgentRunRepository effectiveRunRepository() {
        return runRepository != null ? runRepository : new InMemoryAgentRunRepository();
    }

    private AgentCheckpointRepository effectiveCheckpointRepository() {
        return checkpointRepository != null ? checkpointRepository : new InMemoryAgentCheckpointRepository();
    }

    private AgentWorkspaceResolver effectiveWorkspaceResolver(AgentRuntimeProperties props) {
        return workspaceResolver != null ? workspaceResolver : new AgentWorkspaceResolver(props);
    }

    private TraceRecorder effectiveTraceRecorder() {
        return traceRecorder != null ? traceRecorder : new InMemoryTraceRecorder();
    }

    private BudgetGuard effectiveBudgetGuard(AgentRuntimeProperties props) {
        return budgetGuard != null ? budgetGuard : new DefaultBudgetGuard(props);
    }

    private AgentMetrics effectiveAgentMetrics() {
        return agentMetrics != null ? agentMetrics : new NoopAgentMetrics();
    }

    private ToolOutputSanitizer effectiveToolOutputSanitizer() {
        return toolOutputSanitizer != null ? toolOutputSanitizer : new RegexToolOutputSanitizer();
    }

    private Executor effectiveExecutor() {
        return executor != null ? executor : Runnable::run;
    }

    private ContextWindowManager resolveContextWindowManager(AgentRuntimeProperties props) {
        if (contextWindowManager != null) {
            return contextWindowManager;
        }
        ContextArtifactRepository artifactRepo = contextArtifactRepository != null
                ? contextArtifactRepository : new InMemoryContextArtifactRepository();
        ContextBlobStore blobStore = contextBlobStore != null
                ? contextBlobStore : new InMemoryContextBlobStore();
        ContextWindowManager manager = deepContextSummaryService != null
                ? new ContextWindowManager(props, artifactRepo, blobStore, deepContextSummaryService)
                : new ContextWindowManager(props, artifactRepo, blobStore);
        builtContextWindowManager.set(manager);
        return manager;
    }

    private AgentLoopFactory createAgentLoopFactory(AgentRuntimeProperties props,
                                                     ContextWindowManager cwm) {
        ApprovalStore effectiveApprovalStore = effectiveApprovalStore();
        AgentRunRepository effectiveRunRepo = effectiveRunRepository();
        AgentCheckpointRepository effectiveChkptRepo = effectiveCheckpointRepository();
        AgentLoopStateDependencies state = new AgentLoopStateDependencies(
                effectiveApprovalStore,
                effectiveWorkspaceResolver(props),
                effectiveRunRepo,
                effectiveChkptRepo,
                objectMapper);
        AgentLoopRuntimeDependencies runtime = new AgentLoopRuntimeDependencies(
                props, effectiveTraceRecorder(), effectiveBudgetGuard(props),
                effectiveAgentMetrics(), cwm, effectiveToolOutputSanitizer());
        return new AgentLoopFactory(modelGateway, state, runtime,
                standardHookRegistry(props, null, effectiveApprovalStore, effectiveRunRepo, effectiveChkptRepo), null);
    }

    private AgentLoopFactory createAgentLoopFactory(AgentRuntimeProperties props,
                                                     ContextWindowManager cwm,
                                                     SubAgentControlInbox inbox) {
        ApprovalStore effectiveApprovalStore = effectiveApprovalStore();
        AgentRunRepository effectiveRunRepo = effectiveRunRepository();
        AgentCheckpointRepository effectiveChkptRepo = effectiveCheckpointRepository();
        AgentLoopStateDependencies state = new AgentLoopStateDependencies(
                effectiveApprovalStore,
                effectiveWorkspaceResolver(props),
                effectiveRunRepo,
                effectiveChkptRepo,
                objectMapper);
        AgentLoopRuntimeDependencies runtime = new AgentLoopRuntimeDependencies(
                props, effectiveTraceRecorder(), effectiveBudgetGuard(props),
                effectiveAgentMetrics(), cwm, effectiveToolOutputSanitizer());
        return new AgentLoopFactory(modelGateway, state, runtime,
                standardHookRegistry(props, inbox, effectiveApprovalStore, effectiveRunRepo, effectiveChkptRepo), null);
    }

    private AgentHookRegistry standardHookRegistry(AgentRuntimeProperties props,
                                                    SubAgentControlInbox inbox,
                                                    ApprovalStore approvalStore,
                                                    AgentRunRepository runRepo,
                                                    AgentCheckpointRepository checkpointRepo) {
        SubAgentPartialSummaryGenerator summaryGenerator = new SubAgentPartialSummaryGenerator(objectMapper);
        return new AgentHookRegistry(List.of(
                new MaxStepContinuationStopHook(props),
                new IncompletePlanStopHook(props),
                new PendingApprovalConsistencyStopHook(approvalStore),
                new CheckpointAgentHook(runRepo, checkpointRepo, objectMapper),
                new SubAgentGracefulStopHook(inbox, summaryGenerator)));
    }

    public DefaultAgentLoopService buildAgentLoop() {
        AgentRuntimeProperties props = props();
        Executor exec = effectiveExecutor();
        ContextWindowManager cwm = resolveContextWindowManager(props);
        ToolRegistry registry = new ToolRegistry(tools, new ToolSchemaValidator(objectMapper));

        if (subAgentEnabled) {
            InMemorySubAgentControlInbox inbox = new InMemorySubAgentControlInbox();
            AgentLoopFactory factory = createAgentLoopFactory(props, cwm, inbox);
            SubAgentCoordinator coordinator = resolveSubAgentCoordinator(props, cwm, exec, factory, inbox);
            return factory.createRoot(registry, exec, coordinator);
        }
        AgentLoopFactory factory = createAgentLoopFactory(props, cwm);
        return factory.createStandalone(registry, exec);
    }

    public SubAgentCoordinator buildSubAgentCoordinator() {
        AgentRuntimeProperties props = props();
        Executor exec = effectiveExecutor();
        ContextWindowManager cwm = resolveContextWindowManager(props);
        InMemorySubAgentControlInbox inbox = new InMemorySubAgentControlInbox();
        AgentLoopFactory factory = createAgentLoopFactory(props, cwm, inbox);

        if (subAgentCoordinator != null) {
            return subAgentCoordinator;
        }
        return new SubAgentCoordinator(
                new RoleToolRegistryFactory(tools, new ToolSchemaValidator(objectMapper)),
                factory,
                props,
                objectMapper,
                exec,
                inbox);
    }

    private SubAgentCoordinator resolveSubAgentCoordinator(AgentRuntimeProperties props,
                                                            ContextWindowManager cwm,
                                                            Executor exec,
                                                            AgentLoopFactory factory,
                                                            SubAgentControlInbox inbox) {
        if (subAgentCoordinator != null) {
            return subAgentCoordinator;
        }
        return new SubAgentCoordinator(
                new RoleToolRegistryFactory(tools, new ToolSchemaValidator(objectMapper)),
                factory,
                props,
                objectMapper,
                exec,
                inbox);
    }

    public ContextWindowManager buildContextWindowManager() {
        AgentRuntimeProperties props = props();
        ContextWindowManager resolved = resolveContextWindowManager(props);
        builtContextWindowManager.set(resolved);
        return resolved;
    }

    // ---- 访问器 ----

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

    public ContextWindowManager builtContextWindowManager() {
        return builtContextWindowManager.get();
    }

    // ---- 默认 properties 工厂 ----

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
