package cn.lunalhx.ai.domain.memory.adapter.port;

import cn.lunalhx.ai.domain.memory.model.entity.AgentMemoryGenerationJob;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface AgentMemoryGenerationJobRepository {

    boolean insertOrIgnore(AgentMemoryGenerationJob job);

    Optional<AgentMemoryGenerationJob> claimNextPending(String workerId, Duration leaseDuration);

    boolean transitionToSucceeded(String jobId, String lockedBy);

    boolean transitionToSkipped(String jobId, String lockedBy);

    boolean transitionToRetry(String jobId, int retryCount, Instant notBefore, String errorMsg);

    boolean transitionToFailed(String jobId, int retryCount, String errorMsg);

    int recoverStaleJobs(Duration staleThreshold, int maxRetries);

    Optional<AgentMemoryGenerationJob> findBySourceRunId(String sourceRunId);
}
