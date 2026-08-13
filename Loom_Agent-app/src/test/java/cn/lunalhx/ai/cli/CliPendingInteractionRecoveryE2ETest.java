package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.PendingInteraction;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.service.context.SecretRedactor;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.GrantLifetime;
import cn.lunalhx.ai.domain.tool.model.PermissionDecision;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.service.AuthorizationDisplay;
import cn.lunalhx.ai.domain.tool.service.PermissionPrompt;
import cn.lunalhx.ai.infrastructure.loom.WriteFileTool;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import cn.lunalhx.ai.infrastructure.store.FileConversationHistoryRepository;
import cn.lunalhx.ai.infrastructure.store.FileTraceRecorder;
import cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort;
import cn.lunalhx.ai.test.CliLoopTestFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Ticket 08 seam: pending Tool Approval and user-input pauses persist a
 * redacted display, subject digest, and interaction type, then re-present
 * after Recovery without executing the crash-era call or inventing a second
 * permission system.
 */
public class CliPendingInteractionRecoveryE2ETest {

    private static final String SECRET = "TOPSECRET_pending_xyz";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void pendingApprovalPersistsRedactedDisplayDigestAndType() throws Exception {
        Path workspace = Files.createTempDirectory("pending-approval-persist");
        CountingTool tool = CountingTool.of(new WriteFileTool(new LocalWorkspacePort()));
        RecordingPrompt prompt = RecordingPrompt.crashAfterFirstAsk();
        CliSessionService first = service(workspace,
                writeThenFinalGateway("notes.txt", SECRET, "should-not-finish"),
                List.of(tool.delegate()), prompt, SecretRedactor.of(java.util.Set.of(),
                        java.util.Set.of(SECRET), java.util.Set.of()));
        String sessionId;
        String runId;
        try {
            first.runTurn("write the secret notes");
            sessionId = first.sessionId();
            AgentRun run = first.runRepository().findLatestRootByConversationId(sessionId).orElseThrow();
            runId = run.getRunId();
            assertEquals(AgentRunStatus.WAITING_APPROVAL, run.getStatus());
        } finally {
            first.close();
        }
        assertEquals(0, tool.invocations());
        assertEquals(1, prompt.approvalAsks());
        AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                .latest(runId).orElseThrow();
        PendingInteraction pending = latest.getContextSnapshot().getPendingInteraction();
        assertNotNull(pending);
        assertEquals(PendingInteraction.TOOL_APPROVAL, pending.getInteractionType());
        assertEquals("write_file", pending.getToolName());
        assertNotNull(pending.getRedactedDisplay());
        assertTrue(pending.getRedactedDisplay().contains("write_file"));
        assertTrue(pending.getRedactedDisplay().contains("notes.txt"));
        assertFalse(pending.getRedactedDisplay().contains(SECRET));
        assertNotNull(pending.getSubjectDigest());
        assertEquals(64, pending.getSubjectDigest().length());
        String snapshotJson = mapper.writeValueAsString(latest.getContextSnapshot());
        assertFalse(snapshotJson.contains(SECRET));
    }

    @Test
    public void recoverRepresentsPendingApprovalAndReplansWithoutExecutingOriginalCall()
            throws Exception {
        Path workspace = Files.createTempDirectory("pending-approval-replan");
        CountingTool tool = CountingTool.of(new WriteFileTool(new LocalWorkspacePort()));
        RecordingPrompt firstPrompt = RecordingPrompt.crashAfterFirstAsk();
        CliSessionService first = service(workspace,
                writeThenFinalGateway("notes.txt", SECRET, "should-not-finish"),
                List.of(tool.delegate()), firstPrompt, redactor());
        String sessionId;
        try {
            first.runTurn("write the secret notes");
            sessionId = first.sessionId();
        } finally {
            first.close();
        }
        assertEquals(0, tool.invocations());

        RecordingPrompt recoverPrompt = RecordingPrompt.approve(GrantLifetime.ONCE);
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, sessionId,
                countingFinalGateway("replanned after approval", modelCalls),
                List.of(tool.delegate()), recoverPrompt, redactor());
        try {
            assertTrue(resumed.recoveryRequired());
            assertEquals("replanned after approval", resumed.recover());
            assertEquals(1, recoverPrompt.approvalAsks());
            assertEquals("write_file", recoverPrompt.lastToolName());
            assertFalse(recoverPrompt.lastDisplay().contains(SECRET));
            assertEquals(0, tool.invocations());
            assertEquals(1, modelCalls.get());
            AgentRun recovered = resumed.runRepository()
                    .findLatestRootByConversationId(sessionId).orElseThrow();
            assertEquals(AgentRunStatus.COMPLETED, recovered.getStatus());
            AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(recovered.getRunId()).orElseThrow();
            assertNull(latest.getContextSnapshot().getPendingInteraction());
        } finally {
            resumed.close();
        }
    }

    @Test
    public void recoveredGrantKeepsOriginalScopeAndLaterCallsUseExistingPipeline()
            throws Exception {
        Path workspace = Files.createTempDirectory("pending-approval-grant");
        CountingTool tool = CountingTool.of(new WriteFileTool(new LocalWorkspacePort()));
        RecordingPrompt firstPrompt = RecordingPrompt.crashAfterFirstAsk();
        CliSessionService first = service(workspace,
                writeThenFinalGateway("notes.txt", SECRET, "should-not-finish"),
                List.of(tool.delegate()), firstPrompt, redactor());
        String sessionId;
        try {
            first.runTurn("write the secret notes");
            sessionId = first.sessionId();
        } finally {
            first.close();
        }

        RecordingPrompt recoverPrompt = RecordingPrompt.approve(GrantLifetime.SESSION);
        CliSessionService resumed = resume(workspace, sessionId,
                writeThenFinalGateway("notes.txt", SECRET, "granted-replay-is-new-call"),
                List.of(tool.delegate()), recoverPrompt, redactor());
        try {
            assertEquals("granted-replay-is-new-call", resumed.recover());
            assertEquals(1, recoverPrompt.approvalAsks());
            assertEquals(1, tool.invocations());
            AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(resumed.runRepository()
                            .findLatestRootByConversationId(sessionId).orElseThrow().getRunId())
                    .orElseThrow();
            assertEquals(1, latest.getContextSnapshot().getFrozenAuthorization().permissionGrants().size());
            String json = mapper.writeValueAsString(latest.getContextSnapshot());
            assertFalse(json.contains(SECRET));
        } finally {
            resumed.close();
        }
    }

    @Test
    public void recoveredDenialDoesNotGrantOrExecuteOriginalCall() throws Exception {
        Path workspace = Files.createTempDirectory("pending-approval-deny");
        CountingTool tool = CountingTool.of(new WriteFileTool(new LocalWorkspacePort()));
        RecordingPrompt firstPrompt = RecordingPrompt.crashAfterFirstAsk();
        CliSessionService first = service(workspace,
                writeThenFinalGateway("notes.txt", SECRET, "should-not-finish"),
                List.of(tool.delegate()), firstPrompt, redactor());
        String sessionId;
        try {
            first.runTurn("write the secret notes");
            sessionId = first.sessionId();
        } finally {
            first.close();
        }

        RecordingPrompt recoverPrompt = RecordingPrompt.deny();
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, sessionId,
                countingFinalGateway("replanned after denial", modelCalls),
                List.of(tool.delegate()), recoverPrompt, redactor());
        try {
            assertEquals("replanned after denial", resumed.recover());
            assertEquals(1, recoverPrompt.approvalAsks());
            assertEquals(0, tool.invocations());
            assertEquals(1, modelCalls.get());
            AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(resumed.runRepository()
                            .findLatestRootByConversationId(sessionId).orElseThrow().getRunId())
                    .orElseThrow();
            assertNull(latest.getContextSnapshot().getPendingInteraction());
            assertTrue(latest.getContextSnapshot().getFrozenAuthorization().permissionGrants().isEmpty());
        } finally {
            resumed.close();
        }
    }

    @Test
    public void pendingUserInputPersistsAndIsRepresentedAfterRecover() throws Exception {
        Path workspace = Files.createTempDirectory("pending-user-input");
        RecordingPrompt firstPrompt = RecordingPrompt.approve(GrantLifetime.ONCE);
        AtomicInteger overflowCalls = new AtomicInteger();
        CliSessionService first = service(workspace, overflowThenFinalGateway(overflowCalls, "unused"),
                List.of(), firstPrompt, SecretRedactor.none());
        String sessionId;
        String runId;
        try {
            first.runTurn("huge context please");
            sessionId = first.sessionId();
            AgentRun run = first.runRepository().findLatestRootByConversationId(sessionId).orElseThrow();
            runId = run.getRunId();
            assertEquals(AgentRunStatus.WAITING_USER_INPUT, run.getStatus());
        } finally {
            first.close();
        }
        AgentCheckpoint paused = new FileAgentCheckpointRepository(workspace, mapper)
                .latest(runId).orElseThrow();
        PendingInteraction pending = paused.getContextSnapshot().getPendingInteraction();
        assertNotNull(pending);
        assertEquals(PendingInteraction.USER_INPUT, pending.getInteractionType());
        assertNotNull(pending.getRedactedDisplay());
        assertFalse(pending.getRedactedDisplay().isBlank());
        assertEquals(64, pending.getSubjectDigest().length());

        RecordingPrompt recoverPrompt = RecordingPrompt.approve(GrantLifetime.ONCE);
        recoverPrompt.userInputText = "focus on notes.txt only";
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, sessionId,
                countingFinalGateway("continued after input", modelCalls),
                List.of(), recoverPrompt, SecretRedactor.none());
        try {
            assertTrue(resumed.recoveryRequired());
            assertEquals("continued after input", resumed.recover());
            assertEquals(1, recoverPrompt.userInputAsks());
            assertTrue(recoverPrompt.lastDisplay().contains("补充"));
            assertEquals(1, modelCalls.get());
        } finally {
            resumed.close();
        }
    }

    private SecretRedactor redactor() {
        return SecretRedactor.of(java.util.Set.of(), java.util.Set.of(SECRET), java.util.Set.of());
    }

    private CliSessionService resume(Path workspace, String sessionId, ModelGateway gateway,
                                     List<AgentTool> tools, PermissionPrompt prompt,
                                     SecretRedactor redactor) {
        CliSessionService.CliOptions opts = options(workspace, gateway);
        opts.resumeSessionId = sessionId;
        return service(workspace, opts, gateway, tools, prompt, redactor);
    }

    private ModelGateway countingFinalGateway(String answer, AtomicInteger calls) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                calls.incrementAndGet();
                return Mono.just(ModelChatResult.builder()
                        .content("<final>" + answer + "</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private ModelGateway overflowThenFinalGateway(AtomicInteger calls, String answer) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                int n = calls.incrementAndGet();
                if (n <= 2) {
                    throw new ModelGatewayException(ModelErrorCode.CONTEXT_OVERFLOW,
                            "context_length_exceeded", false, 400, null);
                }
                return Mono.just(ModelChatResult.builder()
                        .content("<final>" + answer + "</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private CliSessionService service(Path workspace, ModelGateway gateway, List<AgentTool> tools,
                                      PermissionPrompt prompt, SecretRedactor redactor) {
        return service(workspace, options(workspace, gateway), gateway, tools, prompt, redactor);
    }

    private CliSessionService service(Path workspace, CliSessionService.CliOptions opts,
                                      ModelGateway gateway, List<AgentTool> tools,
                                      PermissionPrompt prompt, SecretRedactor redactor) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        agent.setApprovalPolicy("ask");
        FileConversationHistoryRepository histories = historyRepository(workspace);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper, gateway, agent,
                List.of(prompt), tools, redactor, histories, checkpoints);
        return new CliSessionService(opts, mapper, agent, new ModelRuntimeProperties(),
                new FileAgentSessionRepository(workspace, mapper),
                new FileAgentRunRepository(workspace, mapper),
                checkpoints, histories,
                new FileTraceRecorder(workspace, mapper), loop);
    }

    private FileConversationHistoryRepository historyRepository(Path workspace) {
        return CliLoopTestFixture.historyRepository(workspace, mapper);
    }

    private CliSessionService.CliOptions options(Path workspace, ModelGateway gateway) {
        CliSessionService.CliOptions o = new CliSessionService.CliOptions();
        o.provider = "deepseek";
        o.model = "deepseek-v4-flash";
        o.baseUrl = "http://unused";
        o.apiKey = "";
        o.workspaceRoot = workspace.toString();
        o.approvalPolicy = "ask";
        o.maxSteps = 6;
        o.maxNewTokens = 512;
        o.temperature = 0.2;
        o.topP = 0.9;
        o.timeoutSeconds = 30;
        o.modelGateway = gateway;
        o.secretValues.add(SECRET);
        return o;
    }

    private ModelGateway writeThenFinalGateway(String path, String content, String answer) {
        AtomicInteger calls = new AtomicInteger();
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"");
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (calls.getAndIncrement() == 0) {
                    return Mono.just(ModelChatResult.builder()
                            .content("<tool>{\"name\":\"write_file\",\"args\":{\"path\":\""
                                    + path + "\",\"content\":\"" + escaped + "\"}}</tool>")
                            .finishReason("stop")
                            .actualModel("deepseek-v4-flash")
                            .build());
                }
                return Mono.just(ModelChatResult.builder()
                        .content("<final>" + answer + "</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private static final class RecordingPrompt implements PermissionPrompt {
        private final AtomicInteger approvalAsks = new AtomicInteger();
        private final AtomicInteger userInputAsks = new AtomicInteger();
        private final List<String> displays = new ArrayList<>();
        private final boolean crashAfterFirstAsk;
        private GrantLifetime approvalLifetime = GrantLifetime.ONCE;
        private String userInputText = "continue with a narrower request";

        private RecordingPrompt(boolean crashAfterFirstAsk) {
            this.crashAfterFirstAsk = crashAfterFirstAsk;
        }

        static RecordingPrompt crashAfterFirstAsk() {
            return new RecordingPrompt(true);
        }

        static RecordingPrompt approve(GrantLifetime lifetime) {
            RecordingPrompt prompt = new RecordingPrompt(false);
            prompt.approvalLifetime = lifetime;
            return prompt;
        }

        static RecordingPrompt deny() {
            RecordingPrompt prompt = new RecordingPrompt(false);
            prompt.approvalLifetime = null;
            return prompt;
        }

        int approvalAsks() {
            return approvalAsks.get();
        }

        int userInputAsks() {
            return userInputAsks.get();
        }

        String lastDisplay() {
            return displays.isEmpty() ? "" : displays.getLast();
        }

        String lastToolName() {
            return lastDisplay().split(" ", 2)[0];
        }

        @Override
        public GrantLifetime ask(AuthorizationDisplay display, PermissionDecision decision) {
            approvalAsks.incrementAndGet();
            displays.add(display.toolName() + " " + display.normalizedSummary());
            if (crashAfterFirstAsk && approvalAsks.get() == 1) {
                throw new SimulatedProcessCrash("during pending approval");
            }
            return approvalLifetime;
        }

        @Override
        public String askUserInput(String redactedDisplay) {
            userInputAsks.incrementAndGet();
            displays.add(redactedDisplay);
            return userInputText;
        }
    }

    private static final class CountingTool implements AgentTool {
        private final AgentTool delegate;
        private final AtomicInteger invocations = new AtomicInteger();

        private CountingTool(AgentTool delegate) {
            this.delegate = delegate;
        }

        static CountingTool of(AgentTool delegate) {
            return new CountingTool(delegate);
        }

        int invocations() {
            return invocations.get();
        }

        AgentTool delegate() {
            return this;
        }

        @Override
        public cn.lunalhx.ai.domain.tool.model.ToolSpec spec() {
            return delegate.spec();
        }

        @Override
        public ToolResult call(ToolCall call) {
            invocations.incrementAndGet();
            return delegate.call(call);
        }
    }

    private static final class SimulatedProcessCrash extends Error {
        SimulatedProcessCrash(String message) {
            super(message);
        }
    }
}
