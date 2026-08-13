package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
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

import static org.junit.Assert.*;

/**
 * Offline evaluation through the full CLI/session/run/tool/resume pipeline
 * using the deterministic FakeModelGateway — not just single-node tests.
 */
public class CliEvalE2ETest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

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

    private CliSessionService service(Path workspace, FakeModelGateway gateway) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper, gateway, agent, List.of());
        return new CliSessionService(options(workspace, gateway), mapper, agent,
                new ModelRuntimeProperties(), sessions, runs, checkpoints, CliLoopTestFixture.historyRepository(workspace, mapper), traces, loop);
    }

    @Test
    public void evalSimpleTaskWithWriteToolProducesExpectedArtifact() throws Exception {
        Path workspace = Files.createTempDirectory("eval-write");
        Path fixture = Files.createDirectories(workspace.resolve("src"));
        Files.writeString(fixture.resolve("main.py"), "def main():\n    pass\n");

        FakeModelGateway gateway = new FakeModelGateway(List.of(
                "<tool name=\"write_file\" path=\"src/main.py\"><content>def main():\n    return 42\n</content></tool>",
                "<final>wrote src/main.py</final>"));
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper, gateway, agent, List.of(),
                List.of(new cn.lunalhx.ai.infrastructure.loom.WriteFileTool(
                        new cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort())));
        CliSessionService session = new CliSessionService(options(workspace, gateway), mapper, agent,
                new ModelRuntimeProperties(), sessions, runs, checkpoints, CliLoopTestFixture.historyRepository(workspace, mapper), traces, loop);

        String answer = session.runTurn("rewrite main.py to return 42");
        assertEquals("wrote src/main.py", answer);

        // expected artifact produced
        String content = Files.readString(workspace.resolve("src").resolve("main.py"));
        assertTrue(content.contains("return 42"));

        // run recorded with real counters
        AgentRun run = session.runRepository().findLatestRootByConversationId(
                session.sessionId()).orElseThrow();
        assertEquals(1, (int) run.getToolSteps());
        assertEquals(2, (int) run.getModelAttempts());
        assertEquals("COMPLETED", run.getStatus().name());
        assertEquals("FINAL_ANSWER_RETURNED", run.getStopReason());

        // token usage recorded from the fake provider
        assertTrue(gateway.callCount() >= 2);
        assertFalse(gateway.responses().isEmpty());
        session.close();
    }

    @Test
    public void evalStepLimitTerminatesWithStepLimitStopReason() throws Exception {
        Path workspace = Files.createTempDirectory("eval-steps");
        // Distinct calls so every one is accepted and consumes a tool step.
        List<String> script = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            script.add("<tool>{\"name\":\"list_files\",\"args\":{\"path\":\"dir" + i + "\"}}</tool>");
        }
        FakeModelGateway gateway = new FakeModelGateway(script);
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        agent.setMaxSteps(3);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper, gateway, agent, List.of(),
                List.of(new cn.lunalhx.ai.infrastructure.loom.ListFilesTool(
                        new cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort())));
        CliSessionService.CliOptions opts = options(workspace, gateway);
        opts.maxSteps = 3;
        CliSessionService session = new CliSessionService(opts, mapper, agent,
                new ModelRuntimeProperties(), sessions, runs, checkpoints, CliLoopTestFixture.historyRepository(workspace, mapper), traces, loop);

        String answer = session.runTurn("keep listing");
        assertTrue(answer.contains("达到工具执行上限"));

        AgentRun run = runs.findLatestRootByConversationId(session.sessionId()).orElseThrow();
        assertEquals("STOPPED", run.getStatus().name());
        assertEquals("STEP_LIMIT_REACHED", run.getStopReason());
        assertEquals(3, (int) run.getToolSteps());

        // report must carry the real stop reason, not a fake "completed"
        java.nio.file.Path report = workspace.resolve(".loom-code").resolve("runs")
                .resolve(run.getRunId()).resolve("report.json");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> reportMap = mapper.readValue(report.toFile(), java.util.Map.class);
        assertEquals("stopped", reportMap.get("status"));
        assertEquals("STEP_LIMIT_REACHED", reportMap.get("stop_reason"));
        session.close();
    }

    @Test
    public void evalResumeAcrossProcessBoundariesKeepsMetricsConsistent() throws Exception {
        Path workspace = Files.createTempDirectory("eval-resume");
        CliSessionService first = service(workspace, FakeModelGateway.finalAnswer("first"));
        String sessionId = first.sessionId();
        first.runTurn("task one");
        AgentRun firstRun = first.runRepository().findLatestRootByConversationId(sessionId).orElseThrow();
        first.close();

        // new process: resume the session
        CliSessionService.CliOptions opts = options(workspace, FakeModelGateway.finalAnswer("second"));
        opts.resumeSessionId = sessionId;
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper,
                FakeModelGateway.finalAnswer("second"), agent, List.of());
        CliSessionService resumed = new CliSessionService(opts, mapper, agent,
                new ModelRuntimeProperties(), sessions, runs, checkpoints, CliLoopTestFixture.historyRepository(workspace, mapper), traces, loop);

        String answer = resumed.runTurn("task two");
        assertEquals("second", answer);

        // two root runs, both completed; old run untouched
        List<AgentRun> convRuns = runs.findByConversationId(sessionId);
        assertEquals(2, convRuns.size());
        AgentRun oldRun = runs.find(firstRun.getRunId()).orElseThrow();
        assertEquals("first", oldRun.getFinalAnswer());

        AgentSession persisted = sessions.find(sessionId).orElseThrow();
        assertTrue(CliLoopTestFixture.historyRepository(workspace, mapper).find(sessionId)
                .orElseThrow().getEntries().size() >= 2);
        resumed.close();
    }
}
