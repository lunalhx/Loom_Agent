package cn.lunalhx.ai.domain.memory.adapter.port;

import cn.lunalhx.ai.domain.memory.model.entity.AgentMemoryGenerationJob;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryGenerationJobStatus;

import java.time.Duration;
import java.util.Optional;

public interface AgentMemoryGenerationJobRepository {

    boolean insertOrIgnore(AgentMemoryGenerationJob job);

    Optional<AgentMemoryGenerationJob> claimNextPending(String workerId, Duration leaseDuration);

    boolean updateStatus(String jobId, MemoryGenerationJobStatus status, String errorMsg, int retryCount);

    int recoverStaleJobs(Duration staleThreshold);

    Optional<AgentMemoryGenerationJob> findBySourceRunId(String sourceRunId);
}
