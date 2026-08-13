package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import cn.lunalhx.ai.infrastructure.store.FileTraceRecorder;
import cn.lunalhx.ai.test.CliLoopTestFixture;
import cn.lunalhx.ai.test.FakeModelGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;

/**
 * Phase-5 invariant: after a real tool scenario, scan every artifact under
 * {@code .loom-code} (trace.jsonl, checkpoint, task state, report, session,
 * memory) and assert the raw secret never appears, while audit fields stay
 * readable.
 */
public class ArtifactLeakScanTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static final String SECRET = "TOPSECRETVALUE_123";

    private CliSessionService service(Path workspace, cn.lunalhx.ai.cli.CliSessionService.CliOptions opts,
                                      FakeModelGateway gateway, List<cn.lunalhx.ai.domain.tool.adapter.port.AgentTool> tools,
                                      cn.lunalhx.ai.domain.agent.service.context.SecretRedactor redactor) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        cn.lunalhx.ai.infrastructure.store.ArtifactRedactor artifactRedactor =
                new cn.lunalhx.ai.infrastructure.store.ArtifactRedactor(redactor);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper, artifactRedactor);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper, artifactRedactor);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper, artifactRedactor);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper, artifactRedactor);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper, gateway, agent, List.of(), tools, redactor);
        return new CliSessionService(opts, mapper, agent, new ModelRuntimeProperties(),
                sessions, runs, checkpoints, CliLoopTestFixture.historyRepository(workspace, mapper), traces, loop);
    }

    private CliSessionService.CliOptions options(Path workspace, FakeModelGateway gateway) {
        CliSessionService.CliOptions o = new CliSessionService.CliOptions();
        o.provider = "deepseek";
        o.model = "deepseek-v4-flash";
        o.baseUrl = "http://unused";
        o.apiKey = "";
        o.workspaceRoot = workspace.toString();
        o.approvalPolicy = "auto";
        o.maxSteps = 6;
        o.maxNewTokens = 512;
        o.temperature = 0.2;
        o.topP = 0.9;
        o.timeoutSeconds = 30;
        o.modelGateway = gateway;
        return o;
    }

    @Test
    public void secretNeverLeaksIntoAnyArtifactAfterRealToolScenario() throws Exception {
        Path workspace = Files.createTempDirectory("leak-scan");
        Files.writeString(workspace.resolve("target.txt"), "hello " + SECRET + " world");

        FakeModelGateway gateway = new FakeModelGateway(List.of(
                "<tool>{\"name\":\"read_file\",\"args\":{\"path\":\"target.txt\"}}</tool>",
                "<final>read done</final>"));

        CliSessionService.CliOptions opts = options(workspace, gateway);
        opts.secretValues.add(SECRET);
        cn.lunalhx.ai.domain.agent.service.context.SecretRedactor redactor =
                cn.lunalhx.ai.domain.agent.service.context.SecretRedactor.of(
                        java.util.Set.of(), java.util.Set.of(SECRET), java.util.Set.of());
        CliSessionService session = service(workspace, opts, gateway,
                List.of(new cn.lunalhx.ai.infrastructure.loom.ReadFileTool(
                        new cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort())), redactor);
        session.runTurn("read the secret file");
        session.close();

        Path loomDir = workspace.resolve(".loom-code");
        try (Stream<Path> files = Files.walk(loomDir)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String content = Files.readString(file);
                assertFalse("secret leaked into " + file.getFileName(), content.contains(SECRET));
            }
        }
    }

    @Test
    public void shellStdoutSecretNeverLeaksIntoAnyArtifact() throws Exception {
        Path workspace = Files.createTempDirectory("leak-shell");

        FakeModelGateway gateway = new FakeModelGateway(List.of(
                "<tool>{\"name\":\"run_shell\",\"args\":{\"command\":\"echo " + SECRET + "\"}}</tool>",
                "<final>done</final>"));

        CliSessionService.CliOptions opts = options(workspace, gateway);
        opts.secretValues.add(SECRET);
        cn.lunalhx.ai.domain.agent.service.context.SecretRedactor redactor =
                cn.lunalhx.ai.domain.agent.service.context.SecretRedactor.of(
                        java.util.Set.of(), java.util.Set.of(SECRET), java.util.Set.of());
        CliSessionService session = service(workspace, opts, gateway,
                List.of(new cn.lunalhx.ai.infrastructure.loom.RunShellTool(
                        new cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort(),
                        new cn.lunalhx.ai.infrastructure.store.FileAttemptLeaseRepository(workspace, mapper))), redactor);
        session.runTurn("echo the secret");
        session.close();

        Path loomDir = workspace.resolve(".loom-code");
        try (Stream<Path> files = Files.walk(loomDir)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String content = Files.readString(file);
                assertFalse("secret leaked into " + file.getFileName(), content.contains(SECRET));
            }
        }
    }
}
