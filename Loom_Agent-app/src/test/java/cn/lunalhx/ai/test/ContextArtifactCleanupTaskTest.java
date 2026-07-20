package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.UserInputAction;
import cn.lunalhx.ai.domain.agent.model.valobj.context.ContextArtifactKind;
import cn.lunalhx.ai.domain.agent.service.context.ContextArtifactPurgeService;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.infrastructure.adapter.cleanup.ContextArtifactCleanupTask;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryContextArtifactRepository;
import cn.lunalhx.ai.infrastructure.context.InMemoryContextBlobStore;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.*;

public class ContextArtifactCleanupTaskTest {

    private InMemoryContextArtifactRepository artifactRepository;
    private InMemoryContextBlobStore blobStore;
    private ContextArtifactPurgeService purgeService;
    private cn.lunalhx.ai.domain.agent.model.valobj.ContextProperties config;
    private StubAgentLoopService agentLoopService;

    @Before
    public void setUp() {
        artifactRepository = new InMemoryContextArtifactRepository();
        blobStore = new InMemoryContextBlobStore();
        purgeService = new ContextArtifactPurgeService(artifactRepository, blobStore);
        config = new cn.lunalhx.ai.domain.agent.model.valobj.ContextProperties();
        config.setTranscriptRetentionHours(168);
        config.setTranscriptCleanupBatchSize(500);
        config.setTranscriptCleanupEnabled(true);
        agentLoopService = new StubAgentLoopService();
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static Instant hoursAgo(int hours) {
        return Instant.now().minusSeconds((long) hours * 3600);
    }

    private ContextArtifact artifact(ContextArtifactKind kind, String conversationId, Instant createdAt) {
        String artifactId = "ctx-" + UUID.randomUUID();
        return ContextArtifact.builder()
                .artifactId(artifactId)
                .runId("run-1")
                .rootRunId("root-1")
                .conversationId(conversationId)
                .kind(kind)
                .storageUri("memory://root-1/" + artifactId)
                .createdAt(createdAt)
                .build();
    }

    private ContextArtifactCleanupTask createTask() {
        return new ContextArtifactCleanupTask(
                artifactRepository, purgeService, agentLoopService, config);
    }

    // ================================================================
    // 1. Only expired TRANSCRIPT artifacts are cleaned
    // ================================================================

    @Test
    public void testOnlyExpiredTranscriptCleaned() {
        artifactRepository.save(artifact(ContextArtifactKind.TRANSCRIPT, "conv-trans", hoursAgo(200)));
        artifactRepository.save(artifact(ContextArtifactKind.TOOL_RESULT, "conv-tool", hoursAgo(200)));
        artifactRepository.save(artifact(ContextArtifactKind.CONTEXT_ENTRY, "conv-entry", hoursAgo(200)));
        artifactRepository.save(artifact(ContextArtifactKind.SKILL_SNAPSHOT, "conv-skill", hoursAgo(200)));

        agentLoopService.hasActiveRuns = false;
        createTask().cleanup();

        assertTrue("TRANSCRIPT artifacts deleted",
                artifactRepository.listByConversationId("conv-trans").isEmpty());
        assertFalse("TOOL_RESULT artifacts remain",
                artifactRepository.listByConversationId("conv-tool").isEmpty());
        assertFalse("CONTEXT_ENTRY artifacts remain",
                artifactRepository.listByConversationId("conv-entry").isEmpty());
        assertFalse("SKILL_SNAPSHOT artifacts remain",
                artifactRepository.listByConversationId("conv-skill").isEmpty());
    }

    // ================================================================
    // 2. Active conversation keeps the newest transcript
    // ================================================================

    @Test
    public void testActiveConversationKeepsLatestTranscript() {
        String convId = "conv-" + UUID.randomUUID();

        ContextArtifact oldest = artifactRepository.save(
                artifact(ContextArtifactKind.TRANSCRIPT, convId, hoursAgo(200)));
        ContextArtifact mid = artifactRepository.save(
                artifact(ContextArtifactKind.TRANSCRIPT, convId, hoursAgo(190)));
        ContextArtifact newest = artifactRepository.save(
                artifact(ContextArtifactKind.TRANSCRIPT, convId, hoursAgo(180)));

        agentLoopService.hasActiveRuns = true;
        createTask().cleanup();

        List<ContextArtifact> remaining = artifactRepository.listByConversationIdAndKind(
                convId, ContextArtifactKind.TRANSCRIPT);
        assertEquals("only newest transcript remains", 1, remaining.size());
        assertEquals("newest artifact kept", newest.getArtifactId(), remaining.get(0).getArtifactId());
    }

    // ================================================================
    // 3. Blob delete failure preserves metadata
    // ================================================================

    @Test
    public void testBlobDeleteFailurePreservesMetadata() {
        ContextBlobStore failingBlobStore = new ContextBlobStore() {
            private final InMemoryContextBlobStore delegate = new InMemoryContextBlobStore();

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
                throw new IllegalStateException("simulated blob delete failure");
            }
        };

        ContextArtifactPurgeService failingPurgeService =
                new ContextArtifactPurgeService(artifactRepository, failingBlobStore);
        ContextArtifactCleanupTask task = new ContextArtifactCleanupTask(
                artifactRepository, failingPurgeService, agentLoopService, config);

        ContextArtifact artifact = artifactRepository.save(
                ContextArtifact.builder()
                        .artifactId("ctx-" + UUID.randomUUID())
                        .runId("run-1")
                        .rootRunId("root-1")
                        .conversationId("conv-" + UUID.randomUUID())
                        .kind(ContextArtifactKind.TRANSCRIPT)
                        .storageUri("memory://root-1/ctx-fail")
                        .createdAt(hoursAgo(200))
                        .build());

        agentLoopService.hasActiveRuns = false;
        task.cleanup();

        assertTrue("artifact metadata preserved after blob delete failure",
                artifactRepository.findByArtifactIdAndRootRunId(
                        artifact.getArtifactId(), artifact.getRootRunId()).isPresent());
    }

    // ================================================================
    // Stub AgentLoopService
    // ================================================================

    static class StubAgentLoopService implements AgentLoopService {
        boolean hasActiveRuns;

        @Override
        public boolean hasActiveRuns(String conversationId) {
            return hasActiveRuns;
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
