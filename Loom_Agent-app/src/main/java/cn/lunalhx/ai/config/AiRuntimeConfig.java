package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.adapter.port.UndoSnapshotRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceSnapshotPort;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceUndoLockRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryStoreProperties;
import cn.lunalhx.ai.domain.agent.service.AgentLoopFactory;
import cn.lunalhx.ai.domain.agent.service.AgentLoopRuntimeDependencies;
import cn.lunalhx.ai.domain.agent.service.AgentLoopService;
import cn.lunalhx.ai.domain.agent.service.AgentLoopStateDependencies;
import cn.lunalhx.ai.domain.agent.service.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.agent.service.UndoSessionCoordinator;
import cn.lunalhx.ai.domain.agent.service.WorkspaceUndoService;
import cn.lunalhx.ai.domain.agent.service.ContextRecallTool;
import cn.lunalhx.ai.domain.agent.service.ContextWindowManager;
import cn.lunalhx.ai.domain.agent.service.DefaultBudgetGuard;
import cn.lunalhx.ai.domain.agent.service.DefaultAgentLoopService;
import cn.lunalhx.ai.domain.agent.service.DefaultReplayService;
import cn.lunalhx.ai.domain.agent.service.DeepContextSummaryService;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryApprovalStore;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryContextArtifactRepository;
import cn.lunalhx.ai.infrastructure.context.InMemoryContextBlobStore;
import cn.lunalhx.ai.infrastructure.adapter.port.InMemorySubAgentControlInbox;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder;
import cn.lunalhx.ai.domain.agent.service.ReplayService;
import cn.lunalhx.ai.domain.agent.flow.hook.UndoSnapshotAgentHook;
import cn.lunalhx.ai.domain.agent.service.RoleToolRegistryFactory;
import cn.lunalhx.ai.domain.agent.service.SubAgentCoordinator;
import cn.lunalhx.ai.domain.conversation.service.ChatStreamService;
import cn.lunalhx.ai.domain.conversation.service.DefaultChatStreamService;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.service.OutputFormatValidator;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.trigger.http.StreamRequestLimiter;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryUndoSnapshotRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryWorkspaceUndoLockRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisApprovalStore;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisContextArtifactRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisUndoSnapshotRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisWorkspaceUndoLockRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisTraceRecorder;
import cn.lunalhx.ai.infrastructure.adapter.snapshot.GitWorkspaceSnapshotAdapter;
import cn.lunalhx.ai.infrastructure.adapter.snapshot.UndoSnapshotCleanupTask;
import cn.lunalhx.ai.infrastructure.context.LocalFileContextBlobStore;
import cn.lunalhx.ai.infrastructure.dao.AgentContextArtifactDao;
import cn.lunalhx.ai.infrastructure.dao.AgentPendingApprovalDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunCheckpointDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunDao;
import cn.lunalhx.ai.infrastructure.dao.AgentTraceEventDao;
import cn.lunalhx.ai.infrastructure.dao.AgentUndoSnapshotDao;
import cn.lunalhx.ai.infrastructure.dao.AgentWorkspaceUndoLockDao;
import cn.lunalhx.ai.infrastructure.mcp.McpClientManager;
import cn.lunalhx.ai.infrastructure.metrics.MicrometerAgentMetrics;
import cn.lunalhx.ai.infrastructure.tool.RegexToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AiRuntimeConfig {

    private static final Logger log = LoggerFactory.getLogger(AiRuntimeConfig.class);

    @Bean
    @ConfigurationProperties(prefix = "loom.ai")
    public ModelRuntimeProperties modelRuntimeProperties() {
        return new ModelRuntimeProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "loom.agent")
    public AgentRuntimeProperties agentRuntimeProperties() {
        return new AgentRuntimeProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "loom.agent.persistence")
    public PersistenceProperties persistenceProperties() {
        return new PersistenceProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "loom.agent.memory-store")
    public MemoryStoreProperties memoryStoreProperties() {
        return new MemoryStoreProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "loom.http.stream-limit")
    public StreamRequestLimitProperties streamRequestLimitProperties() {
        return new StreamRequestLimitProperties();
    }

    @Bean
    public OutputFormatValidator outputFormatValidator(ObjectMapper objectMapper) {
        return new OutputFormatValidator(objectMapper);
    }

    @Bean
    public ToolRegistry toolRegistry(List<AgentTool> tools,
                                     ObjectProvider<McpClientManager> mcpClientManagerProvider) {
        List<AgentTool> allTools = new ArrayList<>(tools);
        McpClientManager mcpManager = mcpClientManagerProvider.getIfAvailable();
        if (mcpManager != null) {
            allTools.addAll(mcpManager.tools());
        }
        return new ToolRegistry(allTools);
    }

    @Bean
    public RoleToolRegistryFactory roleToolRegistryFactory(List<AgentTool> tools,
                                                            ObjectProvider<McpClientManager> mcpClientManagerProvider) {
        List<AgentTool> allTools = new ArrayList<>(tools);
        McpClientManager mcpManager = mcpClientManagerProvider.getIfAvailable();
        if (mcpManager != null) {
            allTools.addAll(mcpManager.tools());
        }
        return new RoleToolRegistryFactory(allTools);
    }

    @Bean
    public AgentRunRepository agentRunRepository(PersistenceProperties persistence,
                                                  ObjectProvider<AgentRunDao> daoProvider,
                                                  MemoryStoreProperties memoryStoreProperties) {
        AgentRunDao dao = daoProvider.getIfAvailable();
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("AgentRunRepository: InMemory (mode=memory)");
                yield new InMemoryAgentRunRepository(memoryStoreProperties);
            }
            case MYSQL -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=mysql requires AgentRunDao, but MyBatis DAO is not available");
                }
                log.info("AgentRunRepository: MyBatis (mode=mysql)");
                yield new MybatisAgentRunRepository(dao);
            }
            case AUTO -> {
                if (dao != null) {
                    log.info("AgentRunRepository: MyBatis (mode=auto, DAO available)");
                    yield new MybatisAgentRunRepository(dao);
                }
                log.info("AgentRunRepository: InMemory (mode=auto, DAO unavailable)");
                yield new InMemoryAgentRunRepository(memoryStoreProperties);
            }
        };
    }

    @Bean
    public AgentCheckpointRepository agentCheckpointRepository(PersistenceProperties persistence,
                                                                ObjectProvider<AgentRunCheckpointDao> daoProvider,
                                                                ObjectMapper objectMapper,
                                                                MemoryStoreProperties memoryStoreProperties) {
        AgentRunCheckpointDao dao = daoProvider.getIfAvailable();
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("AgentCheckpointRepository: InMemory (mode=memory)");
                yield new InMemoryAgentCheckpointRepository(memoryStoreProperties);
            }
            case MYSQL -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=mysql requires AgentRunCheckpointDao, but MyBatis DAO is not available");
                }
                log.info("AgentCheckpointRepository: MyBatis (mode=mysql)");
                yield new MybatisAgentCheckpointRepository(dao, objectMapper);
            }
            case AUTO -> {
                if (dao != null) {
                    log.info("AgentCheckpointRepository: MyBatis (mode=auto, DAO available)");
                    yield new MybatisAgentCheckpointRepository(dao, objectMapper);
                }
                log.info("AgentCheckpointRepository: InMemory (mode=auto, DAO unavailable)");
                yield new InMemoryAgentCheckpointRepository(memoryStoreProperties);
            }
        };
    }

    @Bean
    public ApprovalStore approvalStore(PersistenceProperties persistence,
                                        ObjectProvider<AgentPendingApprovalDao> daoProvider,
                                        ObjectMapper objectMapper,
                                        MemoryStoreProperties memoryStoreProperties) {
        AgentPendingApprovalDao dao = daoProvider.getIfAvailable();
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("ApprovalStore: InMemory (mode=memory)");
                yield new InMemoryApprovalStore(memoryStoreProperties);
            }
            case MYSQL -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=mysql requires AgentPendingApprovalDao, but MyBatis DAO is not available");
                }
                log.info("ApprovalStore: MyBatis (mode=mysql)");
                yield new MybatisApprovalStore(dao, objectMapper);
            }
            case AUTO -> {
                if (dao != null) {
                    log.info("ApprovalStore: MyBatis (mode=auto, DAO available)");
                    yield new MybatisApprovalStore(dao, objectMapper);
                }
                log.info("ApprovalStore: InMemory (mode=auto, DAO unavailable)");
                yield new InMemoryApprovalStore(memoryStoreProperties);
            }
        };
    }

    @Bean
    public TraceRecorder traceRecorder(PersistenceProperties persistence,
                                        ObjectProvider<AgentTraceEventDao> daoProvider,
                                        ObjectMapper objectMapper,
                                        MemoryStoreProperties memoryStoreProperties) {
        AgentTraceEventDao dao = daoProvider.getIfAvailable();
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("TraceRecorder: InMemory (mode=memory)");
                yield new InMemoryTraceRecorder(memoryStoreProperties);
            }
            case MYSQL -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=mysql requires AgentTraceEventDao, but MyBatis DAO is not available");
                }
                log.info("TraceRecorder: MyBatis (mode=mysql)");
                yield new MybatisTraceRecorder(dao, objectMapper);
            }
            case AUTO -> {
                if (dao != null) {
                    log.info("TraceRecorder: MyBatis (mode=auto, DAO available)");
                    yield new MybatisTraceRecorder(dao, objectMapper);
                }
                log.info("TraceRecorder: InMemory (mode=auto, DAO unavailable)");
                yield new InMemoryTraceRecorder(memoryStoreProperties);
            }
        };
    }

    @Bean
    public ContextArtifactRepository contextArtifactRepository(PersistenceProperties persistence,
                                                                ObjectProvider<AgentContextArtifactDao> daoProvider,
                                                                MemoryStoreProperties memoryStoreProperties) {
        AgentContextArtifactDao dao = daoProvider.getIfAvailable();
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("ContextArtifactRepository: InMemory (mode=memory)");
                yield new InMemoryContextArtifactRepository(memoryStoreProperties);
            }
            case MYSQL -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=mysql requires AgentContextArtifactDao, but MyBatis DAO is not available");
                }
                log.info("ContextArtifactRepository: MyBatis (mode=mysql)");
                yield new MybatisContextArtifactRepository(dao);
            }
            case AUTO -> {
                if (dao != null) {
                    log.info("ContextArtifactRepository: MyBatis (mode=auto, DAO available)");
                    yield new MybatisContextArtifactRepository(dao);
                }
                log.info("ContextArtifactRepository: InMemory (mode=auto, DAO unavailable)");
                yield new InMemoryContextArtifactRepository(memoryStoreProperties);
            }
        };
    }

    @Bean
    public ContextBlobStore contextBlobStore(PersistenceProperties persistence,
                                              AgentRuntimeProperties agentRuntimeProperties,
                                              ObjectProvider<AgentContextArtifactDao> artifactDaoProvider,
                                              MemoryStoreProperties memoryStoreProperties) {
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("ContextBlobStore: InMemory (mode=memory)");
                yield new InMemoryContextBlobStore(memoryStoreProperties);
            }
            case MYSQL -> {
                log.info("ContextBlobStore: LocalFile (mode=mysql)");
                yield new LocalFileContextBlobStore(agentRuntimeProperties.getContext().getStorageRoot());
            }
            case AUTO -> {
                AgentContextArtifactDao dao = artifactDaoProvider.getIfAvailable();
                if (dao != null) {
                    log.info("ContextBlobStore: LocalFile (mode=auto, DAO available)");
                    yield new LocalFileContextBlobStore(agentRuntimeProperties.getContext().getStorageRoot());
                }
                log.info("ContextBlobStore: InMemory (mode=auto, DAO unavailable)");
                yield new InMemoryContextBlobStore(memoryStoreProperties);
            }
        };
    }

    @Bean
    public InitializingBean persistenceValidator(PersistenceProperties persistence,
                                                  ObjectProvider<AgentRunDao> runDaoProvider,
                                                  ObjectProvider<AgentRunCheckpointDao> checkpointDaoProvider,
                                                  ObjectProvider<AgentPendingApprovalDao> approvalDaoProvider,
                                                  ObjectProvider<AgentTraceEventDao> traceDaoProvider,
                                                  ObjectProvider<AgentContextArtifactDao> artifactDaoProvider,
                                                  AgentRuntimeProperties agentRuntimeProperties) {
        return () -> {
            boolean runOk = runDaoProvider.getIfAvailable() != null;
            boolean checkpointOk = checkpointDaoProvider.getIfAvailable() != null;
            boolean approvalOk = approvalDaoProvider.getIfAvailable() != null;
            boolean traceOk = traceDaoProvider.getIfAvailable() != null;
            boolean artifactOk = artifactDaoProvider.getIfAvailable() != null;
            boolean allDaosOk = runOk && checkpointOk && approvalOk && traceOk && artifactOk;

            log.info("Persistence mode={}, required={}", persistence.getMode(), persistence.getRequired());
            log.info("DAOs — AgentRunDao={}, AgentRunCheckpointDao={}, AgentPendingApprovalDao={}, AgentTraceEventDao={}, AgentContextArtifactDao={}",
                    status(runOk), status(checkpointOk), status(approvalOk), status(traceOk), status(artifactOk));

            if (persistence.isExplicitMysql()) {
                if (!allDaosOk) {
                    List<String> missing = new ArrayList<>();
                    if (!runOk) missing.add("AgentRunDao");
                    if (!checkpointOk) missing.add("AgentRunCheckpointDao");
                    if (!approvalOk) missing.add("AgentPendingApprovalDao");
                    if (!traceOk) missing.add("AgentTraceEventDao");
                    if (!artifactOk) missing.add("AgentContextArtifactDao");
                    throw new IllegalStateException(
                            "persistence mode=mysql requires all MyBatis DAOs, missing: " + String.join(", ", missing));
                }
                String storageRoot = agentRuntimeProperties.getContext().getStorageRoot();
                if (StringUtils.isBlank(storageRoot)) {
                    throw new IllegalStateException(
                            "persistence mode=mysql requires AGENT_CONTEXT_STORAGE_ROOT to be configured");
                }
                log.info("ContextBlobStore storage root: {}", storageRoot);
            }

            if (persistence.isAuto() && !allDaosOk) {
                if (Boolean.TRUE.equals(persistence.getRequired())) {
                    List<String> missing = new ArrayList<>();
                    if (!runOk) missing.add("AgentRunDao");
                    if (!checkpointOk) missing.add("AgentRunCheckpointDao");
                    if (!approvalOk) missing.add("AgentPendingApprovalDao");
                    if (!traceOk) missing.add("AgentTraceEventDao");
                    if (!artifactOk) missing.add("AgentContextArtifactDao");
                    throw new IllegalStateException(
                            "persistence mode=auto with required=true cannot fall back to memory, missing DAOs: "
                                    + String.join(", ", missing));
                }
                log.warn("Some MyBatis DAOs unavailable — falling back to InMemory implementations (mode=auto, required=false)");
            }

            if (persistence.isExplicitMemory()) {
                log.info("Persistence mode=memory — all agent state stored in memory only");
            }
        };
    }

    @Bean
    public DeepContextSummaryService deepContextSummaryService(ModelGateway modelGateway,
                                                               AgentRuntimeProperties agentRuntimeProperties,
                                                               BudgetGuard budgetGuard,
                                                               TraceRecorder traceRecorder) {
        return new DeepContextSummaryService(modelGateway, agentRuntimeProperties, budgetGuard, traceRecorder);
    }

    @Bean
    public ContextWindowManager contextWindowManager(AgentRuntimeProperties agentRuntimeProperties,
                                                     ContextArtifactRepository contextArtifactRepository,
                                                     ContextBlobStore contextBlobStore,
                                                     DeepContextSummaryService deepContextSummaryService) {
        return new ContextWindowManager(agentRuntimeProperties, contextArtifactRepository, contextBlobStore,
                deepContextSummaryService);
    }

    @Bean
    public ContextRecallTool contextRecallTool(ContextArtifactRepository contextArtifactRepository,
                                               ContextBlobStore contextBlobStore) {
        return new ContextRecallTool(contextArtifactRepository, contextBlobStore);
    }

    @Bean
    public BudgetGuard budgetGuard(AgentRuntimeProperties agentRuntimeProperties,
                                   ModelRuntimeProperties modelRuntimeProperties,
                                   MemoryStoreProperties memoryStoreProperties) {
        return new DefaultBudgetGuard(agentRuntimeProperties, modelRuntimeProperties, memoryStoreProperties);
    }

    @Bean
    public ReplayService replayService(TraceRecorder traceRecorder) {
        return new DefaultReplayService(traceRecorder);
    }

    @Bean
    public StreamRequestLimiter streamRequestLimiter(StreamRequestLimitProperties properties) {
        StreamRequestLimiter.Config config = new StreamRequestLimiter.Config();
        config.enabled = properties.isEnabled();
        config.clientIdHeader = properties.getClientIdHeader();
        config.trustForwardedHeaders = properties.isTrustForwardedHeaders();
        config.maxClientStates = properties.getMaxClientStates();
        config.clientStateTtlSeconds = properties.getClientStateTtlSeconds();
        config.agentAsk = toEndpointLimit(properties.getAgentAsk());
        config.chatStream = toEndpointLimit(properties.getChatStream());
        return new StreamRequestLimiter(config);
    }

    private StreamRequestLimiter.EndpointLimit toEndpointLimit(StreamRequestLimitProperties.EndpointLimit p) {
        return new StreamRequestLimiter.EndpointLimit(
                p.getMaxConcurrentGlobal(), p.getMaxConcurrentPerClient(),
                p.getMaxStartsPerWindow(), p.getWindowSeconds());
    }

    @Bean
    public AgentMetrics agentMetrics(MeterRegistry meterRegistry) {
        return new MicrometerAgentMetrics(meterRegistry);
    }

    @Bean
    public ToolOutputSanitizer toolOutputSanitizer() {
        return new RegexToolOutputSanitizer();
    }

    @Bean
    public AgentWorkspaceResolver agentWorkspaceResolver(AgentRuntimeProperties agentRuntimeProperties) {
        return new AgentWorkspaceResolver(agentRuntimeProperties);
    }

    @Bean
    public ChatStreamService chatStreamService(ModelGateway modelGateway,
                                               ModelRuntimeProperties modelRuntimeProperties,
                                               OutputFormatValidator outputFormatValidator,
                                               Environment environment) {
        String model = environment.getProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash");
        Double temperature = environment.getProperty("spring.ai.deepseek.chat.temperature", Double.class, 0.7D);
        Integer maxTokens = environment.getProperty("spring.ai.deepseek.chat.max-tokens", Integer.class, 2048);
        return new DefaultChatStreamService(modelGateway, modelRuntimeProperties, outputFormatValidator, model, temperature, maxTokens);
    }

    @Bean
    public AgentLoopStateDependencies agentLoopStateDependencies(ApprovalStore approvalStore,
                                                                  AgentWorkspaceResolver agentWorkspaceResolver,
                                                                  AgentRunRepository agentRunRepository,
                                                                  AgentCheckpointRepository agentCheckpointRepository,
                                                                  ObjectMapper objectMapper) {
        return new AgentLoopStateDependencies(approvalStore, agentWorkspaceResolver, agentRunRepository,
                agentCheckpointRepository, objectMapper);
    }

    @Bean
    public AgentLoopRuntimeDependencies agentLoopRuntimeDependencies(AgentRuntimeProperties agentRuntimeProperties,
                                                                       TraceRecorder traceRecorder,
                                                                       BudgetGuard budgetGuard,
                                                                       AgentMetrics agentMetrics,
                                                                       ContextWindowManager contextWindowManager,
                                                                       ToolOutputSanitizer toolOutputSanitizer) {
        return new AgentLoopRuntimeDependencies(agentRuntimeProperties, traceRecorder, budgetGuard,
                agentMetrics, contextWindowManager, toolOutputSanitizer);
    }

    @Bean
    public AgentLoopFactory agentLoopFactory(ModelGateway modelGateway,
                                             AgentLoopStateDependencies state,
                                             AgentLoopRuntimeDependencies runtime,
                                             UndoSessionCoordinator undoSessionCoordinator,
                                             UndoSnapshotAgentHook undoSnapshotAgentHook) {
        return new AgentLoopFactory(modelGateway, state, runtime, undoSessionCoordinator, undoSnapshotAgentHook);
    }

    @Bean
    public AgentLoopService agentLoopService(AgentLoopFactory agentLoopFactory,
                                             ToolRegistry toolRegistry,
                                             ThreadPoolExecutor threadPoolExecutor,
                                             SubAgentCoordinator subAgentCoordinator) {
        return agentLoopFactory.createRoot(toolRegistry, threadPoolExecutor, subAgentCoordinator);
    }

    @Bean
    public SubAgentCoordinator subAgentCoordinator(RoleToolRegistryFactory roleToolRegistryFactory,
                                                   AgentLoopFactory agentLoopFactory,
                                                   AgentRuntimeProperties agentRuntimeProperties,
                                                   ObjectMapper objectMapper,
                                                   ThreadPoolExecutor threadPoolExecutor,
                                                   MemoryStoreProperties memoryStoreProperties) {
        InMemorySubAgentControlInbox inbox = new InMemorySubAgentControlInbox(memoryStoreProperties);
        return new SubAgentCoordinator(roleToolRegistryFactory, agentLoopFactory,
                agentRuntimeProperties, objectMapper, threadPoolExecutor, inbox);
    }

    @Bean
    public WorkspaceSnapshotPort workspaceSnapshotPort(AgentRuntimeProperties agentRuntimeProperties) {
        return new GitWorkspaceSnapshotAdapter(agentRuntimeProperties.getUndo());
    }

    @Bean
    public UndoSnapshotRepository undoSnapshotRepository(PersistenceProperties persistence,
                                                          ObjectProvider<AgentUndoSnapshotDao> daoProvider,
                                                          ObjectMapper objectMapper,
                                                          MemoryStoreProperties memoryStoreProperties) {
        AgentUndoSnapshotDao dao = daoProvider.getIfAvailable();
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("UndoSnapshotRepository: InMemory (mode=memory)");
                yield new InMemoryUndoSnapshotRepository(memoryStoreProperties);
            }
            case MYSQL -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=mysql requires AgentUndoSnapshotDao, but MyBatis DAO is not available");
                }
                log.info("UndoSnapshotRepository: MyBatis (mode=mysql)");
                yield new MybatisUndoSnapshotRepository(dao, objectMapper);
            }
            case AUTO -> {
                if (dao != null) {
                    log.info("UndoSnapshotRepository: MyBatis (mode=auto, DAO available)");
                    yield new MybatisUndoSnapshotRepository(dao, objectMapper);
                }
                log.info("UndoSnapshotRepository: InMemory (mode=auto, DAO unavailable)");
                yield new InMemoryUndoSnapshotRepository(memoryStoreProperties);
            }
        };
    }

    @Bean
    public WorkspaceUndoLockRepository workspaceUndoLockRepository(PersistenceProperties persistence,
                                                                     ObjectProvider<AgentWorkspaceUndoLockDao> daoProvider,
                                                                     MemoryStoreProperties memoryStoreProperties) {
        AgentWorkspaceUndoLockDao dao = daoProvider.getIfAvailable();
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("WorkspaceUndoLockRepository: InMemory (mode=memory)");
                yield new InMemoryWorkspaceUndoLockRepository();
            }
            case MYSQL -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=mysql requires AgentWorkspaceUndoLockDao, but MyBatis DAO is not available");
                }
                log.info("WorkspaceUndoLockRepository: MyBatis (mode=mysql)");
                yield new MybatisWorkspaceUndoLockRepository(dao);
            }
            case AUTO -> {
                if (dao != null) {
                    log.info("WorkspaceUndoLockRepository: MyBatis (mode=auto, DAO available)");
                    yield new MybatisWorkspaceUndoLockRepository(dao);
                }
                log.info("WorkspaceUndoLockRepository: InMemory (mode=auto, DAO unavailable)");
                yield new InMemoryWorkspaceUndoLockRepository();
            }
        };
    }

    @Bean
    public UndoSessionCoordinator undoSessionCoordinator(WorkspaceSnapshotPort workspaceSnapshotPort,
                                                          UndoSnapshotRepository undoSnapshotRepository,
                                                          WorkspaceUndoLockRepository workspaceUndoLockRepository,
                                                          AgentWorkspaceResolver workspaceResolver,
                                                          AgentRuntimeProperties agentRuntimeProperties) {
        return new UndoSessionCoordinator(workspaceSnapshotPort, undoSnapshotRepository,
                workspaceUndoLockRepository, workspaceResolver, agentRuntimeProperties.getUndo());
    }

    @Bean
    public WorkspaceUndoService workspaceUndoService(UndoSnapshotRepository undoSnapshotRepository,
                                                       WorkspaceUndoLockRepository workspaceUndoLockRepository,
                                                       WorkspaceSnapshotPort workspaceSnapshotPort,
                                                       AgentRunRepository agentRunRepository,
                                                       AgentWorkspaceResolver workspaceResolver) {
        return new WorkspaceUndoService(undoSnapshotRepository, workspaceUndoLockRepository,
                workspaceSnapshotPort, agentRunRepository, workspaceResolver);
    }

    @Bean
    public UndoSnapshotAgentHook undoSnapshotAgentHook(UndoSessionCoordinator undoSessionCoordinator,
                                                         UndoSnapshotRepository undoSnapshotRepository,
                                                         AgentRuntimeProperties agentRuntimeProperties) {
        return new UndoSnapshotAgentHook(undoSessionCoordinator,
                undoSnapshotRepository, agentRuntimeProperties.getUndo());
    }

    @Bean
    public UndoSnapshotCleanupTask undoSnapshotCleanupTask(UndoSnapshotRepository undoSnapshotRepository,
                                                            WorkspaceUndoLockRepository workspaceUndoLockRepository,
                                                            WorkspaceSnapshotPort workspaceSnapshotPort,
                                                            AgentRuntimeProperties agentRuntimeProperties) {
        return new UndoSnapshotCleanupTask(undoSnapshotRepository, workspaceUndoLockRepository,
                workspaceSnapshotPort, agentRuntimeProperties.getUndo());
    }

    @Bean
    public InitializingBean aiConfigValidator(ModelRuntimeProperties modelRuntimeProperties,
                                             AgentRuntimeProperties agentRuntimeProperties,
                                             StreamRequestLimitProperties streamLimitProperties,
                                             Environment environment,
                                             ThreadPoolExecutor threadPoolExecutor) {
        return () -> {
            String chatProvider = environment.getProperty("spring.ai.model.chat", "deepseek");
            if ("none".equalsIgnoreCase(chatProvider)) {
                return;
            }
            String baseUrl = environment.getProperty("spring.ai.deepseek.base-url");
            String apiKey = environment.getProperty("spring.ai.deepseek.api-key");
            String model = environment.getProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash");
            if (StringUtils.isBlank(baseUrl)) {
                throw new IllegalStateException("DEEPSEEK_BASE_URL 不能为空");
            }
            if (StringUtils.isBlank(apiKey)) {
                throw new IllegalStateException("DEEPSEEK_API_KEY 不能为空，请参考 docs/env/.env.example");
            }
            modelRuntimeProperties.normalizeModel(model, "deepseek-v4-flash");
            requirePositive(modelRuntimeProperties.getConnectTimeoutMs(), "AI_CONNECT_TIMEOUT_MS");
            requirePositive(modelRuntimeProperties.getFirstTokenTimeoutMs(), "AI_FIRST_TOKEN_TIMEOUT_MS");
            requirePositive(modelRuntimeProperties.getStreamTimeoutMs(), "AI_STREAM_TIMEOUT_MS");
            if (modelRuntimeProperties.getRetryMaxAttempts() == null || modelRuntimeProperties.getRetryMaxAttempts() < 1) {
                throw new IllegalStateException("AI_RETRY_MAX_ATTEMPTS 必须大于等于 1");
            }
            requirePositive(agentRuntimeProperties.getTotalTimeoutMs(), "AGENT_TOTAL_TIMEOUT_MS");
            requirePositive(agentRuntimeProperties.getStepTimeoutMs(), "AGENT_STEP_TIMEOUT_MS");
            requirePositive(agentRuntimeProperties.getToolTimeoutMs(), "AGENT_TOOL_TIMEOUT_MS");
            requirePositive(agentRuntimeProperties.getApprovalTtlSeconds(), "AGENT_APPROVAL_TTL_SECONDS");
            requirePositive(agentRuntimeProperties.getShellTimeoutMs(), "AGENT_SHELL_TIMEOUT_MS");
            if (agentRuntimeProperties.getShellMaxOutputChars() == null || agentRuntimeProperties.getShellMaxOutputChars() <= 0) {
                throw new IllegalStateException("AGENT_SHELL_MAX_OUTPUT_CHARS 必须大于 0");
            }
            if (agentRuntimeProperties.getShellMaxStderrChars() == null || agentRuntimeProperties.getShellMaxStderrChars() <= 0) {
                throw new IllegalStateException("AGENT_SHELL_MAX_STDERR_CHARS 必须大于 0");
            }
            if (!"DENY".equalsIgnoreCase(agentRuntimeProperties.getHighRiskPolicy())
                    && !"CONFIRM".equalsIgnoreCase(agentRuntimeProperties.getHighRiskPolicy())
                    && !"ALLOW".equalsIgnoreCase(agentRuntimeProperties.getHighRiskPolicy())) {
                throw new IllegalStateException("AGENT_HIGH_RISK_POLICY 仅支持 DENY/CONFIRM/ALLOW");
            }
            String permissionMode = StringUtils.defaultString(agentRuntimeProperties.getPermissionMode(), "SANDBOX").toUpperCase();
            if (!"SANDBOX".equals(permissionMode)
                    && !"ACCEPT_EDITS".equals(permissionMode)
                    && !"BYPASS".equals(permissionMode)) {
                throw new IllegalStateException("AGENT_PERMISSION_MODE 仅支持 SANDBOX/ACCEPT_EDITS/BYPASS");
            }
            AgentRuntimeProperties.ShellCommandProperties sc = agentRuntimeProperties.getShellCommands();
            if (sc != null) {
                String unknownLevel = StringUtils.defaultString(sc.getUnknownLevel(), "WRITE_CONFIRM").toUpperCase();
                if (!"WRITE_CONFIRM".equals(unknownLevel)
                        && !"HIGH_RISK_CONFIRM".equals(unknownLevel)
                        && !"HIGH_RISK_DENY".equals(unknownLevel)) {
                    throw new IllegalStateException("AGENT_SHELL_COMMANDS_UNKNOWN_LEVEL 仅支持 WRITE_CONFIRM/HIGH_RISK_CONFIRM/HIGH_RISK_DENY，禁止 READ_ONLY");
                }
            }
            List<String> allowedShellCommands = agentRuntimeProperties.getAllowedShellCommands();
            boolean hasOldConfig = allowedShellCommands != null && !allowedShellCommands.isEmpty();
            boolean hasNewConfig = sc != null
                    && (isNonDefaultList(sc.getReadOnly())
                    || isNonDefaultList(sc.getWrite())
                    || isNonDefaultList(sc.getHighRisk())
                    || isNonDefaultList(sc.getDeny())
                    || !"WRITE_CONFIRM".equalsIgnoreCase(StringUtils.defaultString(sc.getUnknownLevel())));
            if (hasOldConfig && !hasNewConfig) {
                log.warn("loom.agent.allowed-shell-commands 已废弃，请迁移到 loom.agent.shell-commands.* 分桶配置（readOnly/write/highRisk/deny/unknownLevel）。旧配置不会自动迁移，详见 docs/design/agent-loop.md");
            }
            requirePositive(agentRuntimeProperties.getSubAgentTimeoutMs(), "AGENT_SUB_AGENT_TIMEOUT_MS");
            if (agentRuntimeProperties.getSubAgentMaxChildren() == null || agentRuntimeProperties.getSubAgentMaxChildren() < 1) {
                throw new IllegalStateException("AGENT_SUB_AGENT_MAX_CHILDREN 必须大于等于 1");
            }
            if (agentRuntimeProperties.getSubAgentMaxConcurrency() == null || agentRuntimeProperties.getSubAgentMaxConcurrency() < 1) {
                throw new IllegalStateException("AGENT_SUB_AGENT_MAX_CONCURRENCY 必须大于等于 1");
            }
            if (agentRuntimeProperties.getSubAgentMaxDepth() == null || agentRuntimeProperties.getSubAgentMaxDepth() < 1) {
                throw new IllegalStateException("AGENT_SUB_AGENT_MAX_DEPTH 必须大于等于 1");
            }
            if (agentRuntimeProperties.getSubAgentSummaryMaxChars() == null || agentRuntimeProperties.getSubAgentSummaryMaxChars() < 1000) {
                throw new IllegalStateException("AGENT_SUB_AGENT_SUMMARY_MAX_CHARS 必须大于等于 1000");
            }
            requirePositive(agentRuntimeProperties.getContext().getPersistToolResultChars(), "AGENT_CONTEXT_PERSIST_TOOL_RESULT_CHARS");
            requirePositive(agentRuntimeProperties.getContext().getToolPreviewChars(), "AGENT_CONTEXT_TOOL_PREVIEW_CHARS");
            requirePositive(agentRuntimeProperties.getContext().getKeepRecentToolResults(), "AGENT_CONTEXT_KEEP_RECENT_TOOL_RESULTS");
            requirePositive(agentRuntimeProperties.getContext().getMaxDynamicEntries(), "AGENT_CONTEXT_MAX_DYNAMIC_ENTRIES");
            requirePositive(agentRuntimeProperties.getContext().getAutoCompactTokenLimit(), "AGENT_CONTEXT_AUTO_COMPACT_TOKEN_LIMIT");
            requirePositive(agentRuntimeProperties.getContext().getSummaryMaxChars(), "AGENT_CONTEXT_SUMMARY_MAX_CHARS");
            Integer reactiveAttempts = agentRuntimeProperties.getContext().getReactiveCompactMaxAttempts();
            if (reactiveAttempts == null || reactiveAttempts < 0 || reactiveAttempts > 1) {
                throw new IllegalStateException("AGENT_CONTEXT_REACTIVE_COMPACT_MAX_ATTEMPTS 只能是 0 或 1");
            }
            requirePositive(agentRuntimeProperties.getContext().getReactiveKeepRecentEntries(),
                    "AGENT_CONTEXT_REACTIVE_KEEP_RECENT_ENTRIES");
            requirePositive(agentRuntimeProperties.getContext().getContextSafetyMarginTokens(),
                    "AGENT_CONTEXT_SAFETY_MARGIN_TOKENS");
            requirePositive(agentRuntimeProperties.getContext().getDeepSummaryChunkTokenLimit(),
                    "AGENT_CONTEXT_DEEP_SUMMARY_CHUNK_TOKEN_LIMIT");
            requirePositive(agentRuntimeProperties.getContext().getDeepSummaryMaxCalls(),
                    "AGENT_CONTEXT_DEEP_SUMMARY_MAX_CALLS");
            requirePositive(agentRuntimeProperties.getContext().getDeepSummaryMaxOutputTokens(),
                    "AGENT_CONTEXT_DEEP_SUMMARY_MAX_OUTPUT_TOKENS");
            String deepSummaryModel = agentRuntimeProperties.getContext().getDeepSummaryModel();
            if (StringUtils.isNotBlank(deepSummaryModel)
                    && !modelRuntimeProperties.getAllowedModels().contains(deepSummaryModel)) {
                throw new IllegalStateException("AGENT_CONTEXT_DEEP_SUMMARY_MODEL 必须存在于 allowed-models");
            }
            String contextFallbackModel = agentRuntimeProperties.getModelRecovery().getContextFallbackModel();
            if (StringUtils.isNotBlank(contextFallbackModel)) {
                if (!modelRuntimeProperties.getAllowedModels().contains(contextFallbackModel)) {
                    throw new IllegalStateException("AGENT_CONTEXT_FALLBACK_MODEL 必须存在于 allowed-models");
                }
                Long currentLength = modelRuntimeProperties.capability(model).getContextLength();
                Long fallbackLength = modelRuntimeProperties.capability(contextFallbackModel).getContextLength();
                if (currentLength == null || fallbackLength == null || fallbackLength <= currentLength) {
                    throw new IllegalStateException("AGENT_CONTEXT_FALLBACK_MODEL 的 context-length 必须大于默认模型");
                }
            }
            if (threadPoolExecutor.getMaximumPoolSize() < agentRuntimeProperties.getSubAgentMaxConcurrency() + 1) {
                throw new IllegalStateException("线程池最大线程数必须大于 AGENT_SUB_AGENT_MAX_CONCURRENCY");
            }
            AgentRuntimeProperties.StepBudgetProperties stepBudget = agentRuntimeProperties.getStepBudget();
            if (stepBudget != null) {
                requirePositive(stepBudget.getMaxSegments(), "AGENT_STEP_BUDGET_MAX_SEGMENTS");
                requirePositive(stepBudget.getChildMaxSegments(), "AGENT_STEP_BUDGET_CHILD_MAX_SEGMENTS");
                requirePositive(stepBudget.getMaxTotalSteps(), "AGENT_STEP_BUDGET_MAX_TOTAL_STEPS");
                requirePositive(stepBudget.getSameActionMaxRepeats(), "AGENT_STEP_BUDGET_SAME_ACTION_MAX_REPEATS");
                requirePositive(stepBudget.getSameFailureMaxRepeats(), "AGENT_STEP_BUDGET_SAME_FAILURE_MAX_REPEATS");
                requirePositive(stepBudget.getNoProgressMaxRounds(), "AGENT_STEP_BUDGET_NO_PROGRESS_MAX_ROUNDS");
                if (stepBudget.getMaxTotalSteps() < agentRuntimeProperties.getMaxSteps()) {
                    throw new IllegalStateException("AGENT_STEP_BUDGET_MAX_TOTAL_STEPS 不能小于 AGENT_MAX_STEPS");
                }
            }
            if (Boolean.TRUE.equals(agentRuntimeProperties.getBudget().getEnabled())) {
                requirePositive(agentRuntimeProperties.getBudget().getMaxTotalTokens(), "AGENT_BUDGET_MAX_TOTAL_TOKENS");
                requirePositive(agentRuntimeProperties.getBudget().getEstimatedCharsPerToken(), "AGENT_BUDGET_ESTIMATED_CHARS_PER_TOKEN");
                if (agentRuntimeProperties.getBudget().getMaxTotalCost() != null) {
                    if (agentRuntimeProperties.getBudget().getMaxTotalCost().signum() <= 0) {
                        throw new IllegalStateException("AGENT_BUDGET_MAX_TOTAL_COST 必须大于 0");
                    }
                    for (String allowedModel : modelRuntimeProperties.getAllowedModels()) {
                        cn.lunalhx.ai.domain.model.valobj.ModelPricing pricing = modelRuntimeProperties.pricing(allowedModel);
                        if (pricing.getInputPricePer1k() == null || pricing.getOutputPricePer1k() == null
                                || pricing.getInputPricePer1k().signum() < 0
                                || pricing.getOutputPricePer1k().signum() < 0
                                || (pricing.getInputPricePer1k().signum() == 0
                                && pricing.getOutputPricePer1k().signum() == 0)) {
                            throw new IllegalStateException("启用金额预算时必须为模型配置有效价格：" + allowedModel);
                        }
                    }
                }
            }
            if (Boolean.TRUE.equals(modelRuntimeProperties.getResilience().getEnabled())) {
                requirePositive(modelRuntimeProperties.getResilience().getRetryMaxAttempts(), "AI_RESILIENCE_RETRY_MAX_ATTEMPTS");
                requirePositive(modelRuntimeProperties.getResilience().getRetryBackoffInitialMs(), "AI_RESILIENCE_RETRY_BACKOFF_INITIAL_MS");
                requirePositive(modelRuntimeProperties.getResilience().getRetryBackoffMaxMs(), "AI_RESILIENCE_RETRY_BACKOFF_MAX_MS");
                requirePositive(modelRuntimeProperties.getResilience().getCircuitSlidingWindowSize(), "AI_RESILIENCE_CIRCUIT_SLIDING_WINDOW_SIZE");
                requirePositive(modelRuntimeProperties.getResilience().getCircuitOpenStateWaitMs(), "AI_RESILIENCE_CIRCUIT_OPEN_STATE_WAIT_MS");
                requirePositive(modelRuntimeProperties.getResilience().getCircuitHalfOpenPermittedCalls(), "AI_RESILIENCE_CIRCUIT_HALF_OPEN_PERMITTED_CALLS");
                String fallbackModel = modelRuntimeProperties.getResilience().getFallbackModel();
                if (StringUtils.isNotBlank(fallbackModel)
                        && !modelRuntimeProperties.getAllowedModels().contains(fallbackModel)) {
                    throw new IllegalStateException("AI_RESILIENCE_FALLBACK_MODEL 必须存在于 allowed-models");
                }
                if (!"current_step".equalsIgnoreCase(modelRuntimeProperties.getResilience().getFallbackStickinessScope())) {
                    throw new IllegalStateException("AI_RESILIENCE_FALLBACK_STICKINESS_SCOPE 第一版仅支持 current_step");
                }
                for (String allowedModel : modelRuntimeProperties.getAllowedModels()) {
                    modelRuntimeProperties.capability(allowedModel);
                }
            }
            if (streamLimitProperties.isEnabled()) {
                validateEndpointLimit(streamLimitProperties.getAgentAsk(), "agent-ask");
                validateEndpointLimit(streamLimitProperties.getChatStream(), "chat-stream");
                if (streamLimitProperties.getClientStateTtlSeconds() <= streamLimitProperties.getAgentAsk().getWindowSeconds()
                        || streamLimitProperties.getClientStateTtlSeconds() <= streamLimitProperties.getChatStream().getWindowSeconds()) {
                    throw new IllegalStateException("loom.http.stream-limit.client-state-ttl-seconds 必须大于各 endpoint 的 window-seconds");
                }
                if (streamLimitProperties.getMaxClientStates() <= 0) {
                    throw new IllegalStateException("loom.http.stream-limit.max-client-states 必须大于 0");
                }
            }
        };
    }

    private void validateEndpointLimit(StreamRequestLimitProperties.EndpointLimit limit, String name) {
        if (limit.getMaxConcurrentGlobal() <= 0) {
            throw new IllegalStateException("loom.http.stream-limit." + name + ".max-concurrent-global 必须大于 0");
        }
        if (limit.getMaxConcurrentPerClient() <= 0) {
            throw new IllegalStateException("loom.http.stream-limit." + name + ".max-concurrent-per-client 必须大于 0");
        }
        if (limit.getMaxStartsPerWindow() <= 0) {
            throw new IllegalStateException("loom.http.stream-limit." + name + ".max-starts-per-window 必须大于 0");
        }
        if (limit.getWindowSeconds() <= 0) {
            throw new IllegalStateException("loom.http.stream-limit." + name + ".window-seconds 必须大于 0");
        }
        if (limit.getMaxConcurrentPerClient() > limit.getMaxConcurrentGlobal()) {
            throw new IllegalStateException("loom.http.stream-limit." + name + ".max-concurrent-per-client 不能大于 max-concurrent-global");
        }
    }

    private void requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalStateException(name + " 必须大于 0");
        }
    }

    private void requirePositive(Integer value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalStateException(name + " 必须大于 0");
        }
    }

    private static String status(boolean ok) {
        return ok ? "available" : "unavailable";
    }

    private static boolean isNonDefaultList(List<String> list) {
        return list != null && !list.isEmpty();
    }

}
