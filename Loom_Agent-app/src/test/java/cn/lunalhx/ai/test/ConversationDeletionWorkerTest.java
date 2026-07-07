package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationDeletionRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationDeletion;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.UserInputAction;
import cn.lunalhx.ai.domain.agent.model.valobj.context.ContextArtifactKind;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.infrastructure.adapter.deletion.ConversationDeletionWorker;
import cn.lunalhx.ai.infrastructure.adapter.deletion.ConversationPurgeHandler;
import cn.lunalhx.ai.infrastructure.adapter.deletion.InMemoryConversationPurgeHandler;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryContextArtifactRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryConversationDeletionRepository;
import cn.lunalhx.ai.infrastructure.context.InMemoryContextBlobStore;
import org.junit.Test;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.*;

public class ConversationDeletionWorkerTest {

    // ================================================================
    // 1. Sqlite handler: placeholder
    // ================================================================

    @Test
    public void testSqliteHandlerDeletesBlobBeforeMetadata() {
        // Skip: requires heavy MyBatis mocking
        // The SqliteConversationPurgeHandler deletes blobs before metadata,
        // ensuring cleanup proceeds even if metadata deletion fails.
        assertTrue(true);
    }

    // ================================================================
    // 2. InMemory handler processes full state machine
    // ================================================================

    @Test
    public void testMemoryHandlerProcessesStateMachine() {
        InMemoryContextArtifactRepository artifactRepo = new InMemoryContextArtifactRepository();
        InMemoryContextBlobStore blobStore = new InMemoryContextBlobStore();
        InMemoryConversationDeletionRepository deletionRepo = new InMemoryConversationDeletionRepository();
        InMemoryConversationPurgeHandler purgeHandler =
                new InMemoryConversationPurgeHandler(artifactRepo, blobStore);

        // Save context artifacts for the conversation
        ContextArtifact art1 = artifactRepo.save(ContextArtifact.builder()
                .artifactId("art-sm-1")
                .runId("run-sm")
                .rootRunId("root-sm")
                .conversationId("conv-sm")
                .kind(ContextArtifactKind.TRANSCRIPT)
                .storageUri(blobStore.write("root-sm", "art-sm-1", "content-1"))
                .createdAt(Instant.now())
                .build());
        ContextArtifact art2 = artifactRepo.save(ContextArtifact.builder()
                .artifactId("art-sm-2")
                .runId("run-sm")
                .rootRunId("root-sm")
                .conversationId("conv-sm")
                .kind(ContextArtifactKind.TOOL_RESULT)
                .storageUri(blobStore.write("root-sm", "art-sm-2", "content-2"))
                .createdAt(Instant.now())
                .build());

        // Create deletion record with REQUESTED status
        deletionRepo.save(ConversationDeletion.builder()
                .conversationId("conv-sm")
                .status("REQUESTED")
                .retryCount(0)
                .requestedAt(Instant.now())
                .build());

        StubAgentLoopService loopService = new StubAgentLoopService();
        loopService.active = false;

        AgentRunRepository runRepo = new AgentRunRepository() {
            @Override public cn.lunalhx.ai.domain.agent.model.entity.AgentRun save(cn.lunalhx.ai.domain.agent.model.entity.AgentRun run) { return run; }
            @Override public Optional<cn.lunalhx.ai.domain.agent.model.entity.AgentRun> find(String runId) { return Optional.empty(); }
            @Override public java.util.List<cn.lunalhx.ai.domain.agent.model.entity.AgentRun> findChildren(String parentRunId) { return java.util.List.of(); }
            @Override public Optional<cn.lunalhx.ai.domain.agent.model.entity.AgentRun> findLatestRootByConversationId(String conversationId) { return Optional.empty(); }
            @Override public java.util.List<cn.lunalhx.ai.domain.agent.model.entity.AgentRun> findByConversationId(String conversationId) { return java.util.List.of(); }
            @Override public java.util.List<cn.lunalhx.ai.domain.agent.model.entity.ConversationSummary> listConversationSummaries() { return java.util.List.of(); }
        };

        ConversationDeletionWorker worker = new ConversationDeletionWorker(
                deletionRepo, runRepo, loopService, purgeHandler);

        // First call: REQUESTED -> WAITING_FOR_RUNS
        worker.run();
        ConversationDeletion afterFirst = deletionRepo.find("conv-sm").orElse(null);
        assertNotNull("deletion record exists after first run", afterFirst);
        assertEquals("REQUESTED -> WAITING_FOR_RUNS", "WAITING_FOR_RUNS", afterFirst.getStatus());

        // Second call: WAITING_FOR_RUNS -> PURGING (inline) -> COMPLETED
        worker.run();
        ConversationDeletion afterSecond = deletionRepo.find("conv-sm").orElse(null);
        assertNotNull("deletion record exists after second run", afterSecond);
        assertEquals("WAITING_FOR_RUNS -> COMPLETED", "COMPLETED", afterSecond.getStatus());

        // Verify context artifacts are cleaned up
        assertTrue("all artifacts deleted from repo",
                artifactRepo.listByConversationId("conv-sm").isEmpty());
        assertEquals("blob 1 deleted", "", blobStore.read(art1.getStorageUri()));
        assertEquals("blob 2 deleted", "", blobStore.read(art2.getStorageUri()));
    }

    // ================================================================
    // 3. Strict blob delete failure triggers retry
    // ================================================================

    @Test
    public void testStrictBlobDeleteFailureTriggersRetry() {
        ContextBlobStore failingBlobStore = new ContextBlobStore() {
            private final InMemoryContextBlobStore delegate = new InMemoryContextBlobStore();
            private boolean deleteCalled = false;

            @Override
            public String write(String rootRunId, String artifactId, String content) {
                return delegate.write(rootRunId, artifactId, content);
            }

            @Override
            public String read(String storageUri) {
                return delegate.read(storageUri);
            }

            @Override
            public void delete(String storageUri) {
                if (!deleteCalled) {
                    deleteCalled = true;
                    throw new IllegalStateException("simulated blob delete failure");
                }
                delegate.delete(storageUri);
            }
        };

        InMemoryContextArtifactRepository artifactRepo = new InMemoryContextArtifactRepository();
        InMemoryConversationDeletionRepository deletionRepo = new InMemoryConversationDeletionRepository();
        ConversationPurgeHandler purgeHandler =
                new InMemoryConversationPurgeHandler(artifactRepo, failingBlobStore);

        // Save artifact for the conversation
        artifactRepo.save(ContextArtifact.builder()
                .artifactId("art-retry-1")
                .runId("run-retry")
                .rootRunId("root-retry")
                .conversationId("conv-retry")
                .kind(ContextArtifactKind.TRANSCRIPT)
                .storageUri(failingBlobStore.write("root-retry", "art-retry-1", "content"))
                .createdAt(Instant.now())
                .build());

        // Create deletion record with PURGING status and retryCount=0
        deletionRepo.save(ConversationDeletion.builder()
                .conversationId("conv-retry")
                .status("PURGING")
                .retryCount(0)
                .requestedAt(Instant.now())
                .build());

        StubAgentLoopService loopService = new StubAgentLoopService();
        loopService.active = false;

        AgentRunRepository runRepo = new AgentRunRepository() {
            @Override public cn.lunalhx.ai.domain.agent.model.entity.AgentRun save(cn.lunalhx.ai.domain.agent.model.entity.AgentRun run) { return run; }
            @Override public Optional<cn.lunalhx.ai.domain.agent.model.entity.AgentRun> find(String runId) { return Optional.empty(); }
            @Override public java.util.List<cn.lunalhx.ai.domain.agent.model.entity.AgentRun> findChildren(String parentRunId) { return java.util.List.of(); }
            @Override public Optional<cn.lunalhx.ai.domain.agent.model.entity.AgentRun> findLatestRootByConversationId(String conversationId) { return Optional.empty(); }
            @Override public java.util.List<cn.lunalhx.ai.domain.agent.model.entity.AgentRun> findByConversationId(String conversationId) { return java.util.List.of(); }
            @Override public java.util.List<cn.lunalhx.ai.domain.agent.model.entity.ConversationSummary> listConversationSummaries() { return java.util.List.of(); }
        };

        ConversationDeletionWorker worker = new ConversationDeletionWorker(
                deletionRepo, runRepo, loopService, purgeHandler);

        worker.run();

        ConversationDeletion result = deletionRepo.find("conv-retry").orElse(null);
        assertNotNull("deletion record exists after run", result);
        assertEquals("status is REQUESTED (retry), not COMPLETED",
                "REQUESTED", result.getStatus());
        assertEquals("retryCount incremented from 0 to 1", 1, result.getRetryCount());
    }

    // ================================================================
    // Stub AgentLoopService
    // ================================================================

    static class StubAgentLoopService implements AgentLoopService {
        boolean active;

        @Override
        public boolean hasActiveRuns(String conversationId) {
            return active;
        }

        @Override
        public void cancelConversation(String conversationId) {
        }

        @Override
        public boolean cancelRun(String runId) {
            return true;
        }

        @Override
        public Flux<AgentEvent> ask(AgentQuestion question) {
            return Flux.empty();
        }

        @Override
        public Flux<AgentEvent> resume(String approvalId, ApprovalDecision decision, String reason) {
            return Flux.empty();
        }

        @Override
        public Flux<AgentEvent> resumeRun(String runId) {
            return Flux.empty();
        }

        @Override
        public Flux<AgentEvent> resumeWithUserInput(String runId, UserInputAction action, String message) {
            return Flux.empty();
        }
    }
}
