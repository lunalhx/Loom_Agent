package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.EvidenceObservationType;
import cn.lunalhx.ai.domain.tool.model.EvidenceRevalidation;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.service.ToolExecutor;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import cn.lunalhx.ai.infrastructure.loom.ListFilesEvidenceVerifier;
import cn.lunalhx.ai.infrastructure.loom.ListFilesTool;
import cn.lunalhx.ai.infrastructure.loom.SearchEvidenceVerifier;
import cn.lunalhx.ai.infrastructure.loom.SearchTool;
import cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort;
import cn.lunalhx.ai.infrastructure.tool.NoopToolOutputSanitizer;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
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

public class DirectoryAndSearchEvidenceTest {

    private static final String SEARCH_QUERY = "TICKET4_SEARCH_MARKER";
    private static final String HIDDEN_SEARCH_ORIGINAL = SEARCH_QUERY + "_HIDDEN_ORIGINAL";
    private static final String HIDDEN_SEARCH_CHANGED = SEARCH_QUERY + "_HIDDEN_CHANGED";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void capturesCompleteDirectoryEvidenceBeforeVisibleOutputClipping() throws Exception {
        Path workspace = Files.createTempDirectory("directory-evidence");
        for (int i = 0; i < 260; i++) {
            Files.writeString(workspace.resolve(String.format("entry-%03d.txt", i)), "entry\n");
        }
        AgentContext context = planContext(workspace, "list-run", "list_files");

        ToolResult result = executor(new ListFilesTool(new LocalWorkspacePort()))
                .execute(context, call("list_files", workspace, mapper.createObjectNode()
                        .put("path", ".")));

        assertTrue(result.isSuccess());
        assertTrue(result.isTruncated());
        assertFalse(result.getObservation().contains("entry-259.txt"));
        assertEquals(1, context.getEvidenceReceipts().size());
        EvidenceReceipt receipt = context.getEvidenceReceipts().get(0);
        EvidenceRevalidation rule = receipt.getRevalidation();
        assertEquals("list_files:directory-entries:v1", rule.getToolSemantics());
        assertEquals(".", receipt.getNormalizedScope());
        assertEquals(".", rule.getRepositoryRelativePath());
        assertTrue(receipt.isComplete());
        assertTrue(receipt.isRevalidatable());
        assertTrue(ListFilesEvidenceVerifier.matches(workspace, receipt));

        Files.delete(workspace.resolve("entry-259.txt"));
        assertFalse(ListFilesEvidenceVerifier.matches(workspace, receipt));
        executor(new ListFilesTool(new LocalWorkspacePort())).execute(context,
                call("list_files", workspace, mapper.createObjectNode().put("path", ".")));
        assertTrue(context.isEvidenceDrift());

        AgentContext failedContext = planContext(workspace, "failed-list-run", "list_files");
        ToolResult failed = executor(new ListFilesTool(new LocalWorkspacePort())).execute(
                failedContext, call("list_files", workspace,
                        mapper.createObjectNode().put("path", "entry-000.txt")));
        assertFalse(failed.isSuccess());
        assertTrue(failedContext.getEvidenceReceipts().isEmpty());
    }

    @Test
    public void capturesCompletePositiveSearchEvidenceBeforeVisibleOutputClipping() throws Exception {
        Path workspace = Files.createTempDirectory("search-evidence");
        for (int i = 0; i < 260; i++) {
            String marker = i == 259 ? HIDDEN_SEARCH_ORIGINAL : SEARCH_QUERY + "_" + i;
            Files.writeString(workspace.resolve(String.format("hit-%03d.txt", i)),
                    marker + "\n");
        }
        AgentContext context = planContext(workspace, "search-run", "search");
        ToolExecutor executor = executor(new SearchTool(new LocalWorkspacePort()));

        ToolResult result = executor.execute(context, searchCall(workspace, SEARCH_QUERY));

        assertTrue(result.isSuccess());
        assertTrue(result.isTruncated());
        assertFalse(result.getObservation().contains(HIDDEN_SEARCH_ORIGINAL));
        assertEquals(1, context.getEvidenceReceipts().size());
        EvidenceReceipt receipt = context.getEvidenceReceipts().get(0);
        EvidenceRevalidation rule = receipt.getRevalidation();
        assertEquals(EvidenceObservationType.SEARCH, rule.getObservationType());
        assertEquals(SEARCH_QUERY, rule.getNormalizedQuery());
        assertEquals(".", rule.getSearchScope());
        assertTrue(rule.getToolSemantics().startsWith("search:rg:"));
        assertNotNull(rule.getEngineVersion());
        assertTrue(receipt.isComplete());
        assertTrue(receipt.isRevalidatable());
        assertTrue(SearchEvidenceVerifier.matches(workspace, receipt));

        Files.writeString(workspace.resolve("hit-259.txt"), HIDDEN_SEARCH_CHANGED + "\n");
        assertFalse(SearchEvidenceVerifier.matches(workspace, receipt));
        executor.execute(context, searchCall(workspace, SEARCH_QUERY));
        assertTrue(context.isEvidenceDrift());
    }

    @Test
    public void capturesSuccessfulNegativeSearchAsEvidenceAndInvalidSearchAsFailure() throws Exception {
        Path workspace = Files.createTempDirectory("negative-search-evidence");
        Files.writeString(workspace.resolve("plain.txt"), "nothing relevant\n");
        AgentContext context = planContext(workspace, "negative-search-run", "search");
        ToolExecutor executor = executor(new SearchTool(new LocalWorkspacePort()));

        ToolResult noMatch = executor.execute(context, searchCall(workspace, "ABSENT_TICKET4_PATTERN"));

        assertTrue(noMatch.isSuccess());
        assertEquals("(no matches)", noMatch.getObservation());
        assertEquals(1, context.getEvidenceReceipts().size());
        EvidenceReceipt receipt = context.getEvidenceReceipts().get(0);
        assertEquals("ABSENT_TICKET4_PATTERN",
                receipt.getRevalidation().getNormalizedQuery());
        assertTrue(SearchEvidenceVerifier.matches(workspace, receipt));

        Files.writeString(workspace.resolve("new-match.txt"), "ABSENT_TICKET4_PATTERN\n");
        assertFalse(SearchEvidenceVerifier.matches(workspace, receipt));

        ToolResult failed = executor.execute(context, searchCall(workspace, "["));
        assertFalse(failed.isSuccess());
        assertEquals(1, context.getEvidenceReceipts().size());
    }

    @Test
    public void planRunPersistsHiddenSearchEvidenceWithoutFullObservation() throws Exception {
        Path workspace = Files.createTempDirectory("directory-search-evidence-e2e");
        for (int i = 0; i < 260; i++) {
            String marker = i == 259 ? HIDDEN_SEARCH_ORIGINAL : SEARCH_QUERY + "_" + i;
            Files.writeString(workspace.resolve(String.format("hit-%03d.txt", i)),
                    marker + "\n");
        }
        AtomicInteger calls = new AtomicInteger();
        Path hiddenFile = workspace.resolve("hit-259.txt");
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
                    writeUnchecked(hiddenFile, HIDDEN_SEARCH_CHANGED + "\n");
                }
                String content = call < 2
                        ? "<tool>{\"name\":\"search\",\"args\":{\"pattern\":\""
                        + SEARCH_QUERY + "\",\"path\":\".\"}}</tool>"
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
        AgentLoopService loop = CliLoopTestFixture.build(
                workspace, mapper, gateway, properties, List.of(),
                List.of(new SearchTool(new LocalWorkspacePort())));

        List<AgentEvent> events = loop.ask(AgentQuestion.builder()
                        .question("search the workspace")
                        .workspace(workspace.toString())
                        .maxSteps(5)
                        .collaborationMode(CollaborationMode.PLAN)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(20));

        assertNotNull(events);
        String runId = events.stream().map(AgentEvent::getRunId)
                .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        AgentContextSnapshot snapshot = checkpoints.latest(runId).orElseThrow().getContextSnapshot();
        assertTrue(snapshot.isEvidenceDrift());
        assertEquals(2, snapshot.getEvidenceReceipts().size());
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        assertTrue(Boolean.TRUE.equals(runs.find(runId).orElseThrow().getEvidenceDrift()));

        Path artifacts = workspace.resolve(".loom-code");
        try (var stream = Files.walk(artifacts)) {
            for (Path artifact : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                String content = Files.readString(artifact);
                assertFalse("raw hidden search result leaked into " + artifact,
                        content.contains(HIDDEN_SEARCH_ORIGINAL)
                                || content.contains(HIDDEN_SEARCH_CHANGED));
            }
        }
    }

    private ToolExecutor executor(cn.lunalhx.ai.domain.tool.adapter.port.AgentTool tool) {
        ToolRegistry registry = new ToolRegistry(List.of(tool), new ToolSchemaValidator(mapper));
        return new ToolExecutor(registry, new NoopToolOutputSanitizer());
    }

    private AgentContext planContext(Path workspace, String runId, String toolName) {
        AgentContext context = new AgentContext();
        context.setRunId(runId);
        context.setRootRunId(runId);
        context.setCollaborationMode(CollaborationMode.PLAN);
        context.setResolvedWorkspace(workspace);
        context.setAllowedTools(new ArrayList<>(List.of(toolName)));
        return context;
    }

    private ToolCall searchCall(Path workspace, String pattern) {
        return call("search", workspace, mapper.createObjectNode()
                .put("pattern", pattern)
                .put("path", "."));
    }

    private ToolCall call(String name, Path workspace, com.fasterxml.jackson.databind.JsonNode input) {
        return ToolCall.builder()
                .name(name)
                .workspaceRoot(workspace)
                .collaborationMode(CollaborationMode.PLAN)
                .input(input)
                .build();
    }

    private void writeUnchecked(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
