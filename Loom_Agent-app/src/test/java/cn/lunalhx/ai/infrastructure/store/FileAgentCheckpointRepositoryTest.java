package cn.lunalhx.ai.infrastructure.store;

import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryAnchor;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.service.context.SecretRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Ticket 14 repository contract: concurrent checkpoint allocation stays unique,
 * and the writer redacts secrets before they hit disk.
 */
public class FileAgentCheckpointRepositoryTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void concurrentSavesAllocateDistinctVersions() throws Exception {
        Path workspace = Files.createTempDirectory("checkpoint-alloc");
        FileAgentCheckpointRepository first = new FileAgentCheckpointRepository(workspace, mapper);
        FileAgentCheckpointRepository second = new FileAgentCheckpointRepository(workspace, mapper);
        int writers = 20;
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger saved = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        try {
            @SuppressWarnings("unchecked")
            Future<AgentCheckpoint>[] results = new Future[writers];
            for (int i = 0; i < writers; i++) {
                FileAgentCheckpointRepository repo = i % 2 == 0 ? first : second;
                results[i] = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    AgentCheckpoint checkpoint = repo.save(checkpoint("run-alloc", "after_tool"));
                    saved.incrementAndGet();
                    return checkpoint;
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            Set<Long> versions = new HashSet<>();
            for (Future<AgentCheckpoint> result : results) {
                AgentCheckpoint checkpoint = result.get(10, TimeUnit.SECONDS);
                assertTrue(versions.add(checkpoint.getVersion()));
            }
            assertEquals(writers, versions.size());
            assertEquals(writers, saved.get());
            assertEquals(Long.valueOf(writers), first.latest("run-alloc").orElseThrow().getVersion());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void writerRedactsSecretsBeforeTheyHitDisk() throws Exception {
        Path workspace = Files.createTempDirectory("checkpoint-redact");
        String secret = "TOPSECRETVALUE_123";
        ArtifactRedactor redactor = new ArtifactRedactor(
                SecretRedactor.of(Set.of(), Set.of(secret), Set.of()));
        FileAgentCheckpointRepository repository =
                new FileAgentCheckpointRepository(workspace, mapper, redactor);

        AgentCheckpoint saved = repository.save(AgentCheckpoint.builder()
                .runId("run-redact")
                .currentNode("prompt_build")
                .reason("after_tool")
                .contextSnapshot(AgentContextSnapshot.builder()
                        .schemaVersion(AgentContextSnapshot.CURRENT_SCHEMA_VERSION)
                        .runId("run-redact")
                        .runModeSnapshot(CollaborationMode.PLAN)
                        .question("inspect " + secret)
                        .finalAnswer("saw " + secret)
                        .historyAnchor(ConversationHistoryAnchor.empty())
                        .build())
                .build());

        Path file = repository.path("run-redact", saved.getVersion());
        String body = Files.readString(file);
        assertFalse(body.contains(secret));
        assertTrue(body.contains("<redacted>"));
        AgentCheckpoint loaded = repository.latest("run-redact").orElseThrow();
        assertFalse(loaded.getContextSnapshot().getQuestion().contains(secret));
        assertFalse(loaded.getContextSnapshot().getFinalAnswer().contains(secret));
    }

    private AgentCheckpoint checkpoint(String runId, String reason) {
        return AgentCheckpoint.builder()
                .runId(runId)
                .currentNode("prompt_build")
                .reason(reason)
                .contextSnapshot(AgentContextSnapshot.builder()
                        .schemaVersion(AgentContextSnapshot.CURRENT_SCHEMA_VERSION)
                        .runId(runId)
                        .runModeSnapshot(CollaborationMode.PLAN)
                        .historyAnchor(ConversationHistoryAnchor.empty())
                        .build())
                .build();
    }
}
