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
                .retryCount(job.getRetryCount())
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
                            .errorMsg(j.getErrorMsg())
                            .createdAt(j.getCreatedAt())
                            .updatedAt(Instant.now())
                            .build();
                    store.put(j.getJobId(), claimed);
                    return claimed;
                });
    }

    @Override
    public boolean transitionToSucceeded(String jobId, String lockedBy) {
        return store.compute(jobId, (id, existing) -> {
            if (existing == null
                    || existing.getStatus() != MemoryGenerationJobStatus.RUNNING
                    || !lockedBy.equals(existing.getLockedBy())) {
                return existing;
            }
            return AgentMemoryGenerationJob.builder()
                    .jobId(existing.getJobId())
                    .sourceRunId(existing.getSourceRunId())
                    .workspaceKey(existing.getWorkspaceKey())
                    .conversationSummaryJson(existing.getConversationSummaryJson())
                    .status(MemoryGenerationJobStatus.SUCCEEDED)
                    .notBefore(existing.getNotBefore())
                    .lockedBy(null)
                    .lockExpiresAt(null)
                    .retryCount(existing.getRetryCount())
                    .errorMsg(null)
                    .createdAt(existing.getCreatedAt())
                    .updatedAt(Instant.now())
                    .build();
        }) != null && store.get(jobId).getStatus() == MemoryGenerationJobStatus.SUCCEEDED;
    }

    @Override
    public boolean transitionToSkipped(String jobId, String lockedBy) {
        return store.compute(jobId, (id, existing) -> {
            if (existing == null
                    || existing.getStatus() != MemoryGenerationJobStatus.RUNNING
                    || !lockedBy.equals(existing.getLockedBy())) {
                return existing;
            }
            return AgentMemoryGenerationJob.builder()
                    .jobId(existing.getJobId())
                    .sourceRunId(existing.getSourceRunId())
                    .workspaceKey(existing.getWorkspaceKey())
                    .conversationSummaryJson(existing.getConversationSummaryJson())
                    .status(MemoryGenerationJobStatus.SKIPPED)
                    .notBefore(existing.getNotBefore())
                    .lockedBy(null)
                    .lockExpiresAt(null)
                    .retryCount(existing.getRetryCount())
                    .errorMsg(null)
                    .createdAt(existing.getCreatedAt())
                    .updatedAt(Instant.now())
                    .build();
        }) != null && store.get(jobId).getStatus() == MemoryGenerationJobStatus.SKIPPED;
    }

    @Override
    public boolean transitionToRetry(String jobId, int retryCount, Instant notBefore, String errorMsg) {
        return store.compute(jobId, (id, existing) -> {
            if (existing == null) {
                return null;
            }
            return AgentMemoryGenerationJob.builder()
                    .jobId(existing.getJobId())
                    .sourceRunId(existing.getSourceRunId())
                    .workspaceKey(existing.getWorkspaceKey())
                    .conversationSummaryJson(existing.getConversationSummaryJson())
                    .status(MemoryGenerationJobStatus.PENDING)
                    .notBefore(notBefore)
                    .lockedBy(null)
                    .lockExpiresAt(null)
                    .retryCount(retryCount)
                    .errorMsg(errorMsg)
                    .createdAt(existing.getCreatedAt())
                    .updatedAt(Instant.now())
                    .build();
        }) != null;
    }

    @Override
    public boolean transitionToFailed(String jobId, int retryCount, String errorMsg) {
        return store.compute(jobId, (id, existing) -> {
            if (existing == null) {
                return null;
            }
            return AgentMemoryGenerationJob.builder()
                    .jobId(existing.getJobId())
                    .sourceRunId(existing.getSourceRunId())
                    .workspaceKey(existing.getWorkspaceKey())
                    .conversationSummaryJson(existing.getConversationSummaryJson())
                    .status(MemoryGenerationJobStatus.FAILED)
                    .notBefore(existing.getNotBefore())
                    .lockedBy(null)
                    .lockExpiresAt(null)
                    .retryCount(retryCount)
                    .errorMsg(errorMsg)
                    .createdAt(existing.getCreatedAt())
                    .updatedAt(Instant.now())
                    .build();
        }) != null;
    }

    @Override
    public int recoverStaleJobs(Duration staleThreshold, int maxRetries) {
        Instant cutoff = Instant.now().minus(staleThreshold);
        int count = 0;
        for (AgentMemoryGenerationJob job : store.values()) {
            if (job.getStatus() == MemoryGenerationJobStatus.RUNNING
                    && job.getLockExpiresAt() != null
                    && job.getLockExpiresAt().isBefore(cutoff)) {
                int newRetryCount = job.getRetryCount() + 1;
                if (newRetryCount > maxRetries) {
                    store.put(job.getJobId(), AgentMemoryGenerationJob.builder()
                            .jobId(job.getJobId())
                            .sourceRunId(job.getSourceRunId())
                            .workspaceKey(job.getWorkspaceKey())
                            .conversationSummaryJson(job.getConversationSummaryJson())
                            .status(MemoryGenerationJobStatus.FAILED)
                            .notBefore(job.getNotBefore())
                            .lockedBy(null)
                            .lockExpiresAt(null)
                            .retryCount(newRetryCount)
                            .errorMsg("Exceeded max retries after stale recovery")
                            .createdAt(job.getCreatedAt())
                            .updatedAt(Instant.now())
                            .build());
                } else {
                    store.put(job.getJobId(), AgentMemoryGenerationJob.builder()
                            .jobId(job.getJobId())
                            .sourceRunId(job.getSourceRunId())
                            .workspaceKey(job.getWorkspaceKey())
                            .conversationSummaryJson(job.getConversationSummaryJson())
                            .status(MemoryGenerationJobStatus.PENDING)
                            .notBefore(Instant.now())
                            .lockedBy(null)
                            .lockExpiresAt(null)
                            .retryCount(newRetryCount)
                            .errorMsg("Stale job recovered")
                            .createdAt(job.getCreatedAt())
                            .updatedAt(Instant.now())
                            .build());
                }
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
