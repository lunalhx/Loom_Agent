package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistory;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryEntry;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ConversationEntryType;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationExecutionGuard;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopFactory;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopRuntimeDependencies;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopStateDependencies;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.workspace.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.infrastructure.gateway.HttpModelGateway;
import cn.lunalhx.ai.infrastructure.store.FileRunStore;
import cn.lunalhx.ai.infrastructure.store.FileSessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationContext;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * CLI session facade backed by {@code .loom-code/sessions/} and
 * {@code .loom-code/runs/}. Each REPL turn reuses the same session; after
 * every turn history, memory and checkpoints are written back from the agent
 * context. {@code /reset} clears history/memory/checkpoints but keeps the
 * session id and workspace.
 */
public class CliSessionService implements AutoCloseable {

    private final AgentLoopService loopService;
    private final AgentContextBuilder contextBuilder;
    private final AgentRuntimeProperties agent;
    private final ModelRuntimeProperties model;
    private final String workspace;
    private final String sessionId;
    private final FileSessionStore sessionStore;
    private final FileRunStore runStore;
    private final ObjectMapper mapper;
    private final CliOptions options;

    private Map<String, Object> session;

    public CliSessionService(ApplicationContext spring, CliOptions options) {
        this.sessionId = options.resumeSessionId != null
                ? options.resumeSessionId : "session-" + currentStamp();
        this.workspace = options.workspaceRoot;
        this.options = options;
        this.mapper = spring.getBean(ObjectMapper.class);
        this.sessionStore = new FileSessionStore(Path.of(workspace), mapper);
        this.runStore = new FileRunStore(Path.of(workspace), mapper);
        this.agent = spring.getBean(AgentRuntimeProperties.class);
        this.model = spring.getBean(ModelRuntimeProperties.class);
        applyOptions(agent, model, options);
        this.session = openOrCreateSession();
        this.contextBuilder = new AgentContextBuilder(spring, agent, model, session);
        this.loopService = contextBuilder.build();
    }

    private Map<String, Object> openOrCreateSession() {
        if (options.resumeSessionId != null) {
            Map<String, Object> loaded = sessionStore.load(sessionId);
            Object ws = loaded.get("workspace_root");
            if (ws != null && !workspace.equals(ws)) {
                throw new OptionsException("session " + sessionId
                        + " belongs to workspace " + ws + ", refusing to switch to " + workspace);
            }
            return loaded;
        }
        Map<String, Object> fresh = FileSessionStore.newSession(workspace);
        return sessionStore.save(fresh);
    }

    /** Run one turn synchronously, persist session + run artifacts, and return the final answer. */
    public String runTurn(String prompt) {
        AgentQuestion question = AgentQuestion.builder()
                .question(prompt)
                .workspace(workspace)
                .maxSteps(options.maxSteps)
                .approvalPolicy(options.approvalPolicy)
                .conversationId(sessionId)
                .build();

        String runId = "run_" + currentStamp() + "-" + UUID.randomUUID().toString().substring(0, 6);
        Map<String, Object> taskState = FileRunStore.newTaskState(runId, "task_" + currentStamp(), prompt);
        runStore.startRun(runId, taskState);
        runStore.appendTrace(runId, trace("run_started", Map.of("task_id", taskState.get("task_id"))));

        StringBuilder answer = new StringBuilder();
        StringBuilder error = new StringBuilder();
        List<AgentEvent> events = loopService.ask(question)
                .collectList()
                .block(Duration.ofMinutes(30));
        if (events != null) {
            for (AgentEvent event : events) {
                if (event.getAnswer() != null && !event.getAnswer().isBlank()) {
                    answer.setLength(0);
                    answer.append(event.getAnswer());
                }
                if (event.getType() == AgentEventType.ERROR && event.getMessage() != null) {
                    error.append(event.getMessage()).append('\n');
                }
                if (event.getType() == AgentEventType.TOOL_CALL && event.getTool() != null) {
                    runStore.appendTrace(runId, trace("tool_executed", Map.of(
                            "name", event.getTool(),
                            "args", event.getInput() == null ? Map.of() : event.getInput())));
                }
            }
        }
        String finalAnswer = answer.length() == 0 && error.length() > 0
                ? "error: " + error.toString().strip()
                : answer.length() == 0 ? "(empty answer)" : answer.toString();
        taskState.put("final_answer", finalAnswer);
        taskState.put("status", "completed");
        runStore.writeTaskState(runId, taskState);
        runStore.appendTrace(runId, trace("run_finished", Map.of(
                "status", "completed", "final_answer", finalAnswer)));
        runStore.writeReport(runId, report(runId, taskState, finalAnswer));

        persistSession();
        return finalAnswer;
    }

    private Map<String, Object> report(String runId, Map<String, Object> taskState, String finalAnswer) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("run_id", runId);
        report.put("status", taskState.get("status"));
        report.put("stop_reason", "final_answer");
        report.put("final_answer", finalAnswer);
        report.put("tool_steps", taskState.get("tool_steps"));
        report.put("task_state", taskState);
        return report;
    }

    private Map<String, Object> trace(String event, Map<String, Object> payload) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("event", event);
        entry.put("created_at", java.time.Instant.now().toString());
        entry.putAll(payload);
        return entry;
    }

    private void persistSession() {
        sessionStore.save(session);
    }

    public void reset() {
        session.put("history", new ArrayList<>());
        session.put("memory", FileSessionStore.defaultMemory());
        session.put("checkpoints", new LinkedHashMap<>());
        persistSession();
    }

    @Override
    public void close() {
        persistSession();
    }

    public String sessionId() {
        return sessionId;
    }

    public Path sessionPath() {
        return sessionStore.path(sessionId);
    }

    private static String currentStamp() {
        return java.time.Instant.now().toString()
                .replace(":", "").replace("-", "").replace(".", "").substring(0, 15);
    }

    private static void applyOptions(AgentRuntimeProperties agent,
                                     ModelRuntimeProperties model,
                                     CliOptions options) {
        agent.setWorkspaceRoot(options.workspaceRoot);
        agent.setAllowedWorkspaceRoots(List.of(options.workspaceRoot));
        agent.setMaxSteps(options.maxSteps);
        agent.setApprovalPolicy(options.approvalPolicy);

        model.setProvider(options.provider);
        model.setDefaultModel(options.model);
        model.setAllowedModels(List.of(options.model));
        ModelRuntimeProperties.ProviderConfig cfg = new ModelRuntimeProperties.ProviderConfig();
        cfg.setBaseUrl(options.baseUrl);
        cfg.setApiKey(options.apiKey);
        cfg.setDefaultModel(options.model);
        cfg.setTemperature(options.temperature);
        cfg.setMaxTokens(options.maxNewTokens);
        model.getProviders().put(options.provider, cfg);
    }

    /**
     * Assembles the loop with session history/memory restored into the
     * context on every turn.
     */
    private static final class AgentContextBuilder {
        private final ApplicationContext spring;
        private final AgentRuntimeProperties agent;
        private final ModelRuntimeProperties model;
        private final Map<String, Object> session;
        private AgentLoopFactory factory;
        private ToolRegistry registry;

        AgentContextBuilder(ApplicationContext spring, AgentRuntimeProperties agent,
                            ModelRuntimeProperties model, Map<String, Object> session) {
            this.spring = spring;
            this.agent = agent;
            this.model = model;
            this.session = session;
        }

        AgentLoopService build() {
            AgentWorkspaceResolver workspaceResolver = spring.getBean(AgentWorkspaceResolver.class);
            AgentRunRepository runRepository = spring.getBean(AgentRunRepository.class);
            AgentCheckpointRepository checkpointRepository = spring.getBean(AgentCheckpointRepository.class);
            TraceRecorder traceRecorder = spring.getBean(TraceRecorder.class);
            BudgetGuard budgetGuard = spring.getBean(BudgetGuard.class);
            AgentMetrics metrics = spring.getBean(AgentMetrics.class);
            ToolOutputSanitizer sanitizer = spring.getBean(ToolOutputSanitizer.class);
            ObjectMapper mapper = spring.getBean(ObjectMapper.class);

            AgentLoopStateDependencies state = new AgentLoopStateDependencies(
                    workspaceResolver, runRepository, checkpointRepository, mapper);
            AgentLoopRuntimeDependencies runtime = new AgentLoopRuntimeDependencies(
                    agent, traceRecorder, budgetGuard, metrics, sanitizer, model);
            ConversationHistoryAppendService ledgerAppendService = spring.getBean(ConversationHistoryAppendService.class);
            ContextManager contextManager = spring.getBean(ContextManager.class);
            ConversationExecutionGuard executionGuard = spring.getBean(ConversationExecutionGuard.class);
            ThreadPoolExecutor executor = spring.getBean(ThreadPoolExecutor.class);
            this.registry = spring.getBean(ToolRegistry.class);
            ModelGateway gateway = HttpModelGateway.fromProperties(model);
            this.factory = new AgentLoopFactory(gateway, state, runtime,
                    ledgerAppendService, contextManager, executionGuard);
            return factory.createStandalone(registry, executor);
        }
    }

    public static class OptionsException extends RuntimeException {
        public OptionsException(String message) {
            super(message);
        }
    }

    /** Resolved, validated runtime options derived from {@link CliArguments}. */
    public static final class CliOptions {
        public String provider;
        public String model;
        public String baseUrl;
        public String apiKey;
        public String workspaceRoot;
        public String approvalPolicy = "ask";
        public int maxSteps = 6;
        public int maxNewTokens = 512;
        public double temperature = 0.2;
        public double topP = 0.9;
        public long timeoutSeconds = 300;
        public String resumeSessionId;
    }
}
