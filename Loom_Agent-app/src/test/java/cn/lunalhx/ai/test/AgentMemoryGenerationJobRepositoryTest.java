package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryGenerationJobRepository;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemoryGenerationJob;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryGenerationJobStatus;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentMemoryGenerationJobRepository;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.*;

public class AgentMemoryGenerationJobRepositoryTest {

    private AgentMemoryGenerationJobRepository repo;

    @Before
    public void setUp() {
        repo = new InMemoryAgentMemoryGenerationJobRepository();
    }

    @Test
    public void shouldInsertJob() {
        AgentMemoryGenerationJob job = newJob("run-1", Instant.now());
        assertTrue(repo.insertOrIgnore(job));
    }

    @Test
    public void shouldClaimNextPending() {
        repo.insertOrIgnore(newJob("run-claim", Instant.now()));
        Optional<AgentMemoryGenerationJob> claimed = repo.claimNextPending("worker-1", Duration.ofMinutes(5));
        assertTrue(claimed.isPresent());
        assertEquals(MemoryGenerationJobStatus.RUNNING, claimed.get().getStatus());
        assertEquals("worker-1", claimed.get().getLockedBy());
        assertNotNull(claimed.get().getLockExpiresAt());
    }

    @Test
    public void shouldTransitionToSucceededWithLockCheck() {
        repo.insertOrIgnore(newJob("run-succ", Instant.now()));
        var claimed = repo.claimNextPending("w1", Duration.ofMinutes(5));
        assertTrue(claimed.isPresent());

        assertFalse(repo.transitionToSucceeded(claimed.get().getJobId(), "w2"));
        assertTrue(repo.transitionToSucceeded(claimed.get().getJobId(), "w1"));

        var found = repo.findBySourceRunId("run-succ");
        assertTrue(found.isPresent());
        assertEquals(MemoryGenerationJobStatus.SUCCEEDED, found.get().getStatus());
    }

    @Test
    public void shouldRecoverStaleJobs() {
        repo.insertOrIgnore(newJob("run-stale", Instant.now()));
        repo.claimNextPending("w1", Duration.ofSeconds(1));

        int recovered = repo.recoverStaleJobs(Duration.ofSeconds(-1), 3);
        assertEquals(1, recovered);

        var afterRecovery = repo.findBySourceRunId("run-stale");
        assertTrue(afterRecovery.isPresent());
        assertEquals(MemoryGenerationJobStatus.PENDING, afterRecovery.get().getStatus());
        assertEquals(1, afterRecovery.get().getRetryCount());
    }

    @Test
    public void shouldOnlyOneWorkerClaim() {
        repo.insertOrIgnore(newJob("run-race", Instant.now()));

        var claimed1 = repo.claimNextPending("w1", Duration.ofMinutes(5));
        var claimed2 = repo.claimNextPending("w2", Duration.ofMinutes(5));

        assertTrue(claimed1.isPresent());
        assertFalse(claimed2.isPresent());
    }

    private AgentMemoryGenerationJob newJob(String sourceRunId, Instant notBefore) {
        return AgentMemoryGenerationJob.builder()
                .jobId(UUID.randomUUID().toString())
                .sourceRunId(sourceRunId)
                .workspaceKey("ws-key")
                .conversationSummaryJson("{\"question\":\"q\"}")
                .status(MemoryGenerationJobStatus.PENDING)
                .notBefore(notBefore)
                .retryCount(0)
                .build();
    }
}
