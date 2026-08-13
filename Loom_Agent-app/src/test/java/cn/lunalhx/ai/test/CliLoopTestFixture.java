package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationHistoryRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.PlanSubmissionHandler;
import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.budget.DefaultBudgetGuard;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationExecutionGuard;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopFactory;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopRuntimeDependencies;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopStateDependencies;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.observability.NoopAgentMetrics;
import cn.lunalhx.ai.domain.agent.service.workspace.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.service.ToolExecutor;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import cn.lunalhx.ai.infrastructure.store.FileConversationHistoryRepository;
import cn.lunalhx.ai.infrastructure.store.FileTraceRecorder;
import cn.lunalhx.ai.infrastructure.tool.NoopToolOutputSanitizer;
import cn.lunalhx.ai.infrastructure.tool.RedactingToolOutputSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Builds a full standalone agent loop wired to file-backed run/checkpoint/
 * trace stores inside a temp workspace — the same wiring the CLI uses, minus
 * the Spring context. Used by the CLI-level offline E2E tests.
 */
public final class CliLoopTestFixture {

    private CliLoopTestFixture() {
    }

    public static AgentLoopService build(Path workspace, ObjectMapper mapper,
                                         ModelGateway gateway,
                                         AgentRuntimeProperties agent,
                                         List<cn.lunalhx.ai.domain.tool.service.PermissionPrompt> ignored) {
        return build(workspace, mapper, gateway, agent, ignored, List.of());
    }

    public static AgentLoopService build(Path workspace, ObjectMapper mapper,
                                         ModelGateway gateway,
                                         AgentRuntimeProperties agent,
                                         List<cn.lunalhx.ai.domain.tool.service.PermissionPrompt> ignored,
                                         List<cn.lunalhx.ai.domain.tool.adapter.port.AgentTool> tools) {
        return build(workspace, mapper, gateway, agent, ignored, tools,
                cn.lunalhx.ai.domain.agent.service.context.SecretRedactor.none());
    }

    /** Build with a shared redactor wired into both the sanitizer and the
     *  artifact writers, mirroring the CLI wiring. */
    public static AgentLoopService build(Path workspace, ObjectMapper mapper,
                                         ModelGateway gateway,
                                         AgentRuntimeProperties agent,
                                         List<cn.lunalhx.ai.domain.tool.service.PermissionPrompt> ignored,
                                         List<cn.lunalhx.ai.domain.tool.adapter.port.AgentTool> tools,
                                         cn.lunalhx.ai.domain.agent.service.context.SecretRedactor redactor) {
        Path root = workspace.toAbsolutePath().normalize();
        cn.lunalhx.ai.infrastructure.store.ArtifactRedactor artifacts =
                new cn.lunalhx.ai.infrastructure.store.ArtifactRedactor(redactor);
        return build(workspace, mapper, gateway, agent, ignored, tools, redactor,
                new FileConversationHistoryRepository(root, mapper, artifacts),
                new FileAgentCheckpointRepository(root, mapper, artifacts));
    }

    public static AgentLoopService build(Path workspace, ObjectMapper mapper,
                                         ModelGateway gateway,
                                         AgentRuntimeProperties agent,
                                         List<cn.lunalhx.ai.domain.tool.service.PermissionPrompt> ignored,
                                         List<cn.lunalhx.ai.domain.tool.adapter.port.AgentTool> tools,
                                         cn.lunalhx.ai.domain.agent.service.context.SecretRedactor redactor,
                                         ConversationHistoryRepository histories,
                                         AgentCheckpointRepository checkpoints) {
        Path root = workspace.toAbsolutePath().normalize();
        AgentWorkspaceResolver resolver = new AgentWorkspaceResolver(agent);
        AgentRunRepository runs = new FileAgentRunRepository(root, mapper,
                new cn.lunalhx.ai.infrastructure.store.ArtifactRedactor(redactor));
        AgentSessionRepository sessions = new FileAgentSessionRepository(root, mapper,
                new cn.lunalhx.ai.infrastructure.store.ArtifactRedactor(redactor));
        TraceRecorder traces = new FileTraceRecorder(root, mapper,
                new cn.lunalhx.ai.infrastructure.store.ArtifactRedactor(redactor));
        BudgetGuard budget = new DefaultBudgetGuard(agent);
        ModelRuntimeProperties model = AgentRuntimeTestFixture.testModelRuntimeProperties();

        AgentLoopStateDependencies state = new AgentLoopStateDependencies(
                resolver, runs, checkpoints, histories,
                new cn.lunalhx.ai.infrastructure.store.FileAttemptLeaseRepository(root, mapper),
                mapper);
        AgentLoopRuntimeDependencies runtime = new AgentLoopRuntimeDependencies(
                agent, traces, budget, new NoopAgentMetrics(),
                new RedactingToolOutputSanitizer(redactor),
                model);
        ConversationHistoryAppendService ledger = new ConversationHistoryAppendService();
        AgentLoopFactory factory = new AgentLoopFactory(gateway, state, runtime, ledger,
                new ContextManager(agent), new ConversationExecutionGuard(), null,
                new cn.lunalhx.ai.cli.FilePlanSubmissionHandler(sessions, runs, mapper));
        ToolRegistry registry = new ToolRegistry(tools, new ToolSchemaValidator(mapper));
        return factory.createStandalone(registry, Runnable::run);
    }

    public static FileConversationHistoryRepository historyRepository(Path workspace, ObjectMapper mapper) {
        return new FileConversationHistoryRepository(workspace, mapper);
    }

    public static AgentRuntimeProperties agentProperties(Path workspace) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setWorkspaceRoot(workspace.toString());
        properties.setAllowedWorkspaceRoots(List.of(workspace.toString()));
        properties.setMaxSteps(6);
        properties.setStepTimeoutMs(60_000L);
        properties.setTotalTimeoutMs(300_000L);
        properties.setToolTimeoutMs(10_000L);
        properties.setObservationMaxChars(8000);
        properties.setApprovalPolicy("never");
        return properties;
    }

    public static ToolOutputSanitizer passthrough() {
        return new NoopToolOutputSanitizer();
    }

    public static Executor inline() {
        return Runnable::run;
    }
}
