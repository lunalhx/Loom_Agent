package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryGenerationJobRepository;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemoryGenerationJob;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryGenerationJobStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryAgentMemoryGenerationJobRepository implements AgentMemoryGenerationJobRepository {

    private final Map<String, AgentMemoryGenerationJob> store = new ConcurrentHashMap<>();

    @Override
    public boolean insertOrIgnore(AgentMemoryGenerationJob job) {
        String id = job.getJobId() != null ? job.getJobId() : UUID.randomUUID().toString();
        boolean exists = store.values().stream().anyMatch(j -> j.getSourceRunId().equals(job.getSourceRunId()));
        if (exists) return false;
        AgentMemoryGenerationJob saved = AgentMemoryGenerationJob.builder()
                .jobId(id)
                .sourceRunId(job.getSourceRunId())
                .workspaceKey(job.getWorkspaceKey())
                .conversationSummaryJson(job.getConversationSummaryJson())
                .status(MemoryGenerationJobStatus.PENDING)
                .notBefore(job.getNotBefore() != null ? job.getNotBefore() : Instant.now())
                .retryCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        store.put(id, saved);
        return true;
    }

    @Override
    public Optional<AgentMemoryGenerationJob> claimNextPending(String workerId, Duration leaseDuration) {
        Instant now = Instant.now();
        return store.values().stream()
                .filter(j -> j.getStatus() == MemoryGenerationJobStatus.PENDING
                        && j.getNotBefore() != null
                        && !j.getNotBefore().isAfter(now))
                .min(Comparator.comparing(AgentMemoryGenerationJob::getNotBefore))
                .map(j -> {
                    AgentMemoryGenerationJob claimed = AgentMemoryGenerationJob.builder()
                            .jobId(j.getJobId())
                            .sourceRunId(j.getSourceRunId())
                            .workspaceKey(j.getWorkspaceKey())
                            .conversationSummaryJson(j.getConversationSummaryJson())
                            .status(MemoryGenerationJobStatus.RUNNING)
                            .notBefore(j.getNotBefore())
                            .lockedBy(workerId)
                            .lockExpiresAt(now.plus(leaseDuration))
                            .retryCount(j.getRetryCount())
                            .createdAt(j.getCreatedAt())
                            .updatedAt(Instant.now())
                            .build();
                    store.put(j.getJobId(), claimed);
                    return claimed;
                });
    }

    @Override
    public boolean updateStatus(String jobId, MemoryGenerationJobStatus status, String errorMsg, int retryCount) {
        AgentMemoryGenerationJob existing = store.get(jobId);
        if (existing == null) return false;
        AgentMemoryGenerationJob updated = AgentMemoryGenerationJob.builder()
                .jobId(existing.getJobId())
                .sourceRunId(existing.getSourceRunId())
                .workspaceKey(existing.getWorkspaceKey())
                .conversationSummaryJson(existing.getConversationSummaryJson())
                .status(status)
                .notBefore(existing.getNotBefore())
                .lockedBy(existing.getLockedBy())
                .lockExpiresAt(existing.getLockExpiresAt())
                .retryCount(retryCount)
                .errorMsg(errorMsg)
                .createdAt(existing.getCreatedAt())
                .updatedAt(Instant.now())
                .build();
        store.put(jobId, updated);
        return true;
    }

    @Override
    public int recoverStaleJobs(Duration staleThreshold) {
        Instant cutoff = Instant.now().minus(staleThreshold);
        int count = 0;
        for (AgentMemoryGenerationJob job : store.values()) {
            if (job.getStatus() == MemoryGenerationJobStatus.RUNNING
                    && job.getLockExpiresAt() != null
                    && job.getLockExpiresAt().isBefore(cutoff)) {
                AgentMemoryGenerationJob recovered = AgentMemoryGenerationJob.builder()
                        .jobId(job.getJobId())
                        .sourceRunId(job.getSourceRunId())
                        .workspaceKey(job.getWorkspaceKey())
                        .conversationSummaryJson(job.getConversationSummaryJson())
                        .status(MemoryGenerationJobStatus.PENDING)
                        .notBefore(job.getNotBefore())
                        .retryCount(job.getRetryCount())
                        .createdAt(job.getCreatedAt())
                        .updatedAt(Instant.now())
                        .build();
                store.put(job.getJobId(), recovered);
                count++;
            }
        }
        return count;
    }

    @Override
    public Optional<AgentMemoryGenerationJob> findBySourceRunId(String sourceRunId) {
        return store.values().stream()
                .filter(j -> j.getSourceRunId().equals(sourceRunId))
                .findFirst();
    }
}
