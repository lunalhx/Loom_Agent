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
    public void shouldIgnoreDuplicateSourceRunId() {
        String runId = "run-dup";
        assertTrue(repo.insertOrIgnore(newJob(runId, Instant.now())));
        assertFalse(repo.insertOrIgnore(newJob(runId, Instant.now())));
    }

    @Test
    public void shouldFindBySourceRunId() {
        String runId = "run-find";
        repo.insertOrIgnore(newJob(runId, Instant.now()));
        Optional<AgentMemoryGenerationJob> found = repo.findBySourceRunId(runId);
        assertTrue(found.isPresent());
        assertEquals(runId, found.get().getSourceRunId());
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
    public void shouldNotClaimBeforeNotBefore() {
        repo.insertOrIgnore(newJob("run-future", Instant.now().plus(Duration.ofHours(1))));
        Optional<AgentMemoryGenerationJob> claimed = repo.claimNextPending("w", Duration.ofMinutes(5));
        assertFalse(claimed.isPresent());
    }

    @Test
    public void shouldTransitionToSucceededWithLockCheck() {
        repo.insertOrIgnore(newJob("run-succ", Instant.now()));
        var claimed = repo.claimNextPending("w1", Duration.ofMinutes(5));
        assertTrue(claimed.isPresent());

        assertFalse("wrong worker should fail", repo.transitionToSucceeded(claimed.get().getJobId(), "w2"));
        assertTrue("correct worker should succeed", repo.transitionToSucceeded(claimed.get().getJobId(), "w1"));

        var found = repo.findBySourceRunId("run-succ");
        assertTrue(found.isPresent());
        assertEquals(MemoryGenerationJobStatus.SUCCEEDED, found.get().getStatus());
        assertNull(found.get().getLockedBy());
        assertNull(found.get().getLockExpiresAt());
        assertNull(found.get().getErrorMsg());
    }

    @Test
    public void shouldTransitionToSkippedWithLockCheck() {
        repo.insertOrIgnore(newJob("run-skip", Instant.now()));
        var claimed = repo.claimNextPending("w1", Duration.ofMinutes(5));
        assertTrue(claimed.isPresent());

        assertFalse(repo.transitionToSkipped(claimed.get().getJobId(), "w2"));
        assertTrue(repo.transitionToSkipped(claimed.get().getJobId(), "w1"));

        var found = repo.findBySourceRunId("run-skip");
        assertEquals(MemoryGenerationJobStatus.SKIPPED, found.get().getStatus());
        assertNull(found.get().getLockedBy());
        assertNull(found.get().getErrorMsg());
    }

    @Test
    public void shouldTransitionToRetry() {
        repo.insertOrIgnore(newJob("run-retry", Instant.now()));
        var claimed = repo.claimNextPending("w1", Duration.ofMinutes(5));
        String jobId = claimed.get().getJobId();

        Instant retryNotBefore = Instant.now().plus(Duration.ofSeconds(30));
        assertTrue(repo.transitionToRetry(jobId, 1, retryNotBefore, "transient error"));

        var found = repo.findBySourceRunId("run-retry");
        assertTrue(found.isPresent());
        assertEquals(MemoryGenerationJobStatus.PENDING, found.get().getStatus());
        assertEquals(1, found.get().getRetryCount());
        assertEquals("transient error", found.get().getErrorMsg());
        assertNull(found.get().getLockedBy());
        assertNull(found.get().getLockExpiresAt());
    }

    @Test
    public void shouldTransitionToFailed() {
        repo.insertOrIgnore(newJob("run-fail", Instant.now()));
        var claimed = repo.claimNextPending("w1", Duration.ofMinutes(5));
        String jobId = claimed.get().getJobId();

        assertTrue(repo.transitionToFailed(jobId, 4, "max retries exceeded"));

        var found = repo.findBySourceRunId("run-fail");
        assertEquals(MemoryGenerationJobStatus.FAILED, found.get().getStatus());
        assertEquals(4, found.get().getRetryCount());
        assertEquals("max retries exceeded", found.get().getErrorMsg());
        assertNull(found.get().getLockedBy());
    }

    @Test
    public void shouldRecoverStaleJobs() {
        repo.insertOrIgnore(newJob("run-stale", Instant.now()));
        var claimed = repo.claimNextPending("w1", Duration.ofSeconds(1));

        // Wait for lock to expire
        var found = repo.findBySourceRunId("run-stale");
        assertTrue(found.isPresent());

        int recovered = repo.recoverStaleJobs(Duration.ofSeconds(-1), 3);
        assertEquals(1, recovered);

        var afterRecovery = repo.findBySourceRunId("run-stale");
        assertTrue(afterRecovery.isPresent());
        assertEquals(MemoryGenerationJobStatus.PENDING, afterRecovery.get().getStatus());
        assertEquals(1, afterRecovery.get().getRetryCount());
    }

    @Test
    public void shouldFailStaleJobsBeyondMaxRetries() {
        repo.insertOrIgnore(newJob("run-stale-max", Instant.now()));
        var claimed = repo.claimNextPending("w1", Duration.ofSeconds(1));

        int recovered = repo.recoverStaleJobs(Duration.ofSeconds(-1), 0);
        assertEquals(1, recovered);

        var afterRecovery = repo.findBySourceRunId("run-stale-max");
        assertTrue(afterRecovery.isPresent());
        assertEquals(MemoryGenerationJobStatus.FAILED, afterRecovery.get().getStatus());
    }

    @Test
    public void shouldOnlyOneWorkerClaim() throws InterruptedException {
        repo.insertOrIgnore(newJob("run-race", Instant.now()));

        var claimed1 = repo.claimNextPending("w1", Duration.ofMinutes(5));
        var claimed2 = repo.claimNextPending("w2", Duration.ofMinutes(5));

        assertTrue(claimed1.isPresent());
        assertFalse("second worker should not claim same job", claimed2.isPresent());
    }

    @Test
    public void terminalTransitionRequiresRunningStatus() {
        repo.insertOrIgnore(newJob("run-not-running", Instant.now()));
        assertFalse(repo.transitionToSucceeded("run-not-running", "w1"));
    }

    @Test
    public void insertOrIgnoreShouldSetTimestamps() {
        repo.insertOrIgnore(newJob("run-ts", Instant.now()));
        var found = repo.findBySourceRunId("run-ts");
        assertTrue(found.isPresent());
        assertNotNull(found.get().getCreatedAt());
        assertNotNull(found.get().getUpdatedAt());
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
