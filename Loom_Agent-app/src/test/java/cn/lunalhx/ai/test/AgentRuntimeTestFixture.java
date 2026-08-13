package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationHistoryRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.adapter.port.PlanSubmissionHandler;
import cn.lunalhx.ai.domain.agent.adapter.port.PlanSubmissionResult;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopFactory;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopRuntimeDependencies;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopStateDependencies;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.workspace.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import cn.lunalhx.ai.domain.agent.service.execution.DefaultAgentLoopService;
import cn.lunalhx.ai.domain.agent.service.budget.DefaultBudgetGuard;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationExecutionGuard;
import cn.lunalhx.ai.domain.agent.service.observability.NoopAgentMetrics;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryDocument;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import cn.lunalhx.ai.infrastructure.tool.NoopToolOutputSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public final class AgentRuntimeTestFixture {

    private static final Path DEFAULT_WORKSPACE = Path.of(".").toAbsolutePath().normalize();

    private ModelGateway modelGateway;
    private List<AgentTool> tools = new ArrayList<>();
    private AgentRuntimeProperties properties;
    private AgentRunRepository runRepository;
    private AgentCheckpointRepository checkpointRepository;
    private ConversationHistoryRepository historyRepository;
    private AgentWorkspaceResolver workspaceResolver;
    private TraceRecorder traceRecorder;
    private BudgetGuard budgetGuard;
    private AgentMetrics agentMetrics;
    private ToolOutputSanitizer toolOutputSanitizer;
    private Executor executor;
    private ConversationExecutionGuard executionGuard;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentRuntimeTestFixture() {
    }

    public static AgentRuntimeTestFixture fixture() {
        return new AgentRuntimeTestFixture();
    }

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

    public AgentRuntimeTestFixture runRepository(AgentRunRepository runRepository) {
        this.runRepository = runRepository;
        return this;
    }

    public AgentRuntimeTestFixture checkpointRepository(AgentCheckpointRepository checkpointRepository) {
        this.checkpointRepository = checkpointRepository;
        return this;
    }

    public AgentRuntimeTestFixture historyRepository(ConversationHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
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

    public AgentRuntimeTestFixture toolOutputSanitizer(ToolOutputSanitizer toolOutputSanitizer) {
        this.toolOutputSanitizer = toolOutputSanitizer;
        return this;
    }

    public AgentRuntimeTestFixture executor(Executor executor) {
        this.executor = executor;
        return this;
    }

    public AgentRuntimeTestFixture executionGuard(ConversationExecutionGuard executionGuard) {
        this.executionGuard = executionGuard;
        return this;
    }

    private AgentRuntimeProperties props() {
        return properties != null ? properties : standardProperties();
    }

    private AgentRunRepository effectiveRunRepository() {
        return runRepository != null ? runRepository : new InMemoryAgentRunRepository();
    }

    private AgentCheckpointRepository effectiveCheckpointRepository() {
        return checkpointRepository != null ? checkpointRepository : new InMemoryAgentCheckpointRepository();
    }

    private ConversationHistoryRepository effectiveHistoryRepository() {
        return historyRepository != null ? historyRepository : new InMemoryConversationHistoryRepository();
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
        return toolOutputSanitizer != null ? toolOutputSanitizer : new NoopToolOutputSanitizer();
    }

    private Executor effectiveExecutor() {
        return executor != null ? executor : Runnable::run;
    }

    private ConversationExecutionGuard effectiveExecutionGuard() {
        return executionGuard != null ? executionGuard : new ConversationExecutionGuard();
    }

    private AgentLoopFactory createAgentLoopFactory(AgentRuntimeProperties props) {
        AgentLoopStateDependencies state = new AgentLoopStateDependencies(
                effectiveWorkspaceResolver(props),
                effectiveRunRepository(),
                effectiveCheckpointRepository(),
                effectiveHistoryRepository(),
                objectMapper);
        AgentLoopRuntimeDependencies runtime = new AgentLoopRuntimeDependencies(
                props, effectiveTraceRecorder(), effectiveBudgetGuard(props),
                effectiveAgentMetrics(), effectiveToolOutputSanitizer(),
                testModelRuntimeProperties(props));
        ConversationHistoryAppendService ledgerAppendService = new ConversationHistoryAppendService();
        return new AgentLoopFactory(modelGateway, state, runtime,
                ledgerAppendService, new ContextManager(props), effectiveExecutionGuard(), null,
                new PlanSubmissionHandler() {
                    @Override
                    public PlanSubmissionResult prepare(cn.lunalhx.ai.domain.agent.model.entity.AgentContext context) {
                        return PlanSubmissionResult.rejected("test fixture has no Session Plan store");
                    }

                    @Override
                    public PlanSubmissionResult commit(cn.lunalhx.ai.domain.agent.model.entity.AgentContext context) {
                        return PlanSubmissionResult.rejected("test fixture has no Session Plan store");
                    }

                    @Override
                    public void abort(cn.lunalhx.ai.domain.agent.model.entity.AgentContext context) {
                        // no-op test handler
                    }
                });
    }

    public DefaultAgentLoopService buildAgentLoop() {
        AgentRuntimeProperties props = props();
        Executor exec = effectiveExecutor();
        ToolRegistry registry = new ToolRegistry(tools, new ToolSchemaValidator(objectMapper));
        AgentLoopFactory factory = createAgentLoopFactory(props);
        return factory.createStandalone(registry, exec);
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public AgentRuntimeProperties properties() {
        return properties;
    }

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

    public static ModelRuntimeProperties testModelRuntimeProperties() {
        return testModelRuntimeProperties(null);
    }

    public static ModelRuntimeProperties testModelRuntimeProperties(AgentRuntimeProperties agent) {
        ModelRuntimeProperties properties = new ModelRuntimeProperties();
        ModelRuntimeProperties.ProviderConfig cfg = new ModelRuntimeProperties.ProviderConfig();
        cfg.setDefaultModel("deepseek-v4-flash");
        cfg.setMaxTokens(2048);
        properties.getProviders().put("deepseek", cfg);
        return properties;
    }

    public static String modelVisibleText(ChatPrompt prompt) {
        StringBuilder text = new StringBuilder();
        if (prompt.getSystemPrompt() != null) {
            text.append(prompt.getSystemPrompt()).append('\n');
        }
        if (prompt.getMessage() != null) {
            text.append(prompt.getMessage()).append('\n');
        }
        if (prompt.getMessages() != null) {
            for (ChatMessage message : prompt.getMessages()) {
                if (message != null && message.getContent() != null) {
                    text.append(message.getContent()).append('\n');
                }
            }
        }
        return text.toString();
    }

    private static final class InMemoryConversationHistoryRepository implements ConversationHistoryRepository {
        private final java.util.Map<String, ConversationHistoryDocument> bySession = new java.util.HashMap<>();

        @Override
        public java.util.Optional<ConversationHistoryDocument> find(String sessionId) {
            return java.util.Optional.ofNullable(bySession.get(sessionId));
        }

        @Override
        public ConversationHistoryDocument save(ConversationHistoryDocument document) {
            bySession.put(document.getSessionId(), document);
            return document;
        }
    }
}
