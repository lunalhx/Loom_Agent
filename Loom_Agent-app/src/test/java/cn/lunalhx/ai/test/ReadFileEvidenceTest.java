package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.service.ToolExecutor;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import cn.lunalhx.ai.infrastructure.loom.ReadFileTool;
import cn.lunalhx.ai.infrastructure.loom.ReadFileEvidenceVerifier;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort;
import cn.lunalhx.ai.infrastructure.tool.NoopToolOutputSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ReadFileEvidenceTest {

    private static final String HIDDEN_ORIGINAL = "HIDDEN_RANGE_ORIGINAL";
    private static final String HIDDEN_CHANGED = "HIDDEN_RANGE_CHANGED";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void capturesCompleteReadEvidenceBeforeModelOutputClipping() throws Exception {
        Path workspace = Files.createTempDirectory("read-evidence");
        writeLongFile(workspace.resolve("long.txt"), HIDDEN_ORIGINAL);
        AgentContext context = planContext(workspace, "run-evidence");

        ToolResult result = executor().execute(context, readCall(workspace, 1, 300));

        assertTrue(result.isSuccess());
        assertTrue(result.isTruncated());
        assertFalse(result.getObservation().contains(HIDDEN_ORIGINAL));
        assertEquals(1, context.getEvidenceReceipts().size());
        EvidenceReceipt receipt = context.getEvidenceReceipts().get(0);
        assertEquals("read_file:utf8-lines:v1", receipt.getToolSemantics());
        assertEquals("long.txt#lines=1-300", receipt.getNormalizedScope());
        assertEquals("long.txt", receipt.getRepositoryRelativePath());
        assertEquals(1, (int) receipt.getObservedStartLine());
        assertEquals(300, (int) receipt.getObservedEndLine());
        assertEquals("run-evidence", receipt.getSourceRunId());
        assertTrue(receipt.isComplete());
        assertTrue(receipt.isRevalidatable());
        assertNotNull(receipt.getRevalidation());
    }

    @Test
    public void sameRunDeduplicatesMatchingReadsAndIrreversiblyMarksDrift() throws Exception {
        Path workspace = Files.createTempDirectory("read-drift");
        Path file = workspace.resolve("long.txt");
        writeLongFile(file, HIDDEN_ORIGINAL);
        AgentContext context = planContext(workspace, "run-drift");
        ToolExecutor executor = executor();
        ToolCall call = readCall(workspace, 1, 300);

        executor.execute(context, call);
        executor.execute(context, call);
        assertEquals(1, context.getEvidenceReceipts().size());
        assertFalse(context.isEvidenceDrift());

        writeLongFile(file, HIDDEN_CHANGED);
        executor.execute(context, call);
        assertEquals(2, context.getEvidenceReceipts().size());
        assertTrue(context.isEvidenceDrift());

        writeLongFile(file, HIDDEN_ORIGINAL);
        executor.execute(context, call);
        assertEquals(2, context.getEvidenceReceipts().size());
        assertTrue(context.isEvidenceDrift());

        AgentContext restored = AgentContextSnapshot.from(context).restore();
        assertEquals(2, restored.getEvidenceReceipts().size());
        assertTrue(restored.isEvidenceDrift());
    }

    @Test
    public void receiptRevalidationUsesTheSameCanonicalObservedRange() throws Exception {
        Path workspace = Files.createTempDirectory("read-revalidate");
        Path file = workspace.resolve("long.txt");
        writeLongFile(file, HIDDEN_ORIGINAL);
        AgentContext context = planContext(workspace, "run-revalidate");

        executor().execute(context, readCall(workspace, 1, 300));
        EvidenceReceipt receipt = context.getEvidenceReceipts().get(0);
        assertTrue(ReadFileEvidenceVerifier.matches(workspace, receipt));

        writeLongFile(file, HIDDEN_CHANGED);
        assertFalse(ReadFileEvidenceVerifier.matches(workspace, receipt));
    }

    @Test
    public void buildAndFailedReadsDoNotCreatePrecisePlanEvidence() throws Exception {
        Path workspace = Files.createTempDirectory("read-evidence-policy");
        writeLongFile(workspace.resolve("long.txt"), HIDDEN_ORIGINAL);

        AgentContext build = planContext(workspace, "build-run");
        build.setCollaborationMode(CollaborationMode.BUILD);
        ToolExecutor executor = executor();
        assertTrue(executor.execute(build, readCall(workspace, 1, 300)).isSuccess());
        assertTrue(build.getEvidenceReceipts().isEmpty());

        AgentContext failed = planContext(workspace, "failed-run");
        ToolResult result = executor.execute(failed,
                ToolCall.builder()
                        .name("read_file")
                        .workspaceRoot(workspace)
                        .input(mapper.createObjectNode().put("path", "missing.txt"))
                        .build());
        assertFalse(result.isSuccess());
        assertTrue(failed.getEvidenceReceipts().isEmpty());
    }

    @Test
    public void planRunIntegrationPersistsReceiptAndDriftWithoutRawFileContent() throws Exception {
        Path workspace = Files.createTempDirectory("read-evidence-e2e");
        Path file = workspace.resolve("long.txt");
        writeLongFile(file, HIDDEN_ORIGINAL);
        AtomicInteger calls = new AtomicInteger();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public Flux<cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk> stream(
                    cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(
                    cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt prompt) {
                int call = calls.getAndIncrement();
                if (call == 1) {
                    writeLongFileUnchecked(file, HIDDEN_CHANGED);
                }
                String content = call < 2
                        ? "<tool>{\"name\":\"read_file\",\"args\":{\"path\":\"long.txt\",\"start\":1,\"end\":300}}</tool>"
                        : "<final>research complete</final>";
                return Mono.just(ModelChatResult.builder()
                        .content(content)
                        .finishReason("stop")
                        .actualModel("offline-test")
                        .usage(TokenUsage.builder().promptTokens(10).completionTokens(5).totalTokens(15).build())
                        .build());
            }
        };
        AgentRuntimeProperties properties = CliLoopTestFixture.agentProperties(workspace);
        ObjectMapper testMapper = new ObjectMapper().findAndRegisterModules();
        AgentLoopService loop = CliLoopTestFixture.build(
                workspace, testMapper, gateway, properties, List.of(),
                List.of(new ReadFileTool(new LocalWorkspacePort())));

        List<AgentEvent> events = loop.ask(AgentQuestion.builder()
                        .question("inspect long.txt")
                        .workspace(workspace.toString())
                        .maxSteps(5)
                        .collaborationMode(CollaborationMode.PLAN)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(20));

        assertNotNull(events);
        String runId = events.stream().map(AgentEvent::getRunId)
                .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, testMapper);
        AgentContextSnapshot snapshot = checkpoints.latest(runId).orElseThrow().getContextSnapshot();
        assertTrue(snapshot.isEvidenceDrift());
        assertEquals(2, snapshot.getEvidenceReceipts().size());
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, testMapper);
        assertTrue(Boolean.TRUE.equals(runs.find(runId).orElseThrow().getEvidenceDrift()));

        Path artifacts = workspace.resolve(".loom-code");
        try (var stream = Files.walk(artifacts)) {
            for (Path artifact : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                String content = Files.readString(artifact);
                assertFalse("raw file content leaked into " + artifact,
                        content.contains(HIDDEN_ORIGINAL) || content.contains(HIDDEN_CHANGED));
            }
        }
    }

    private ToolExecutor executor() {
        ToolRegistry registry = new ToolRegistry(
                List.of(new ReadFileTool(new LocalWorkspacePort())),
                new ToolSchemaValidator(mapper));
        return new ToolExecutor(registry, new NoopToolOutputSanitizer());
    }

    private AgentContext planContext(Path workspace, String runId) {
        AgentContext context = new AgentContext();
        context.setRunId(runId);
        context.setRootRunId(runId);
        context.setCollaborationMode(CollaborationMode.PLAN);
        context.setResolvedWorkspace(workspace);
        context.setAllowedTools(new ArrayList<>(List.of("read_file")));
        return context;
    }

    private ToolCall readCall(Path workspace, int start, int end) {
        return ToolCall.builder()
                .name("read_file")
                .workspaceRoot(workspace)
                .collaborationMode(CollaborationMode.PLAN)
                .input(mapper.createObjectNode()
                        .put("path", "long.txt")
                        .put("start", start)
                        .put("end", end))
                .build();
    }

    private void writeLongFile(Path file, String hiddenLine) throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 300; i++) {
            lines.add(i == 250 ? hiddenLine : "visible-line-" + i + "-" + "x".repeat(48));
        }
        Files.writeString(file, String.join("\n", lines));
    }

    private void writeLongFileUnchecked(Path file, String hiddenLine) {
        try {
            writeLongFile(file, hiddenLine);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
