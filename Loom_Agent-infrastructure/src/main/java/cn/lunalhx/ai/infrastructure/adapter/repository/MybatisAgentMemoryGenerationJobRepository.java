package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryGenerationJobRepository;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemoryGenerationJob;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryGenerationJobStatus;
import cn.lunalhx.ai.infrastructure.dao.AgentMemoryGenerationJobDao;
import cn.lunalhx.ai.infrastructure.dao.po.AgentMemoryGenerationJobPO;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

public class MybatisAgentMemoryGenerationJobRepository implements AgentMemoryGenerationJobRepository {

    private final AgentMemoryGenerationJobDao dao;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_INSTANT;

    public MybatisAgentMemoryGenerationJobRepository(AgentMemoryGenerationJobDao dao) {
        this.dao = dao;
    }

    @Override
    public boolean insertOrIgnore(AgentMemoryGenerationJob job) {
        AgentMemoryGenerationJobPO po = toPo(job);
        po.setJobId(job.getJobId() != null ? job.getJobId() : UUID.randomUUID().toString());
        return dao.insertOrIgnore(po) > 0;
    }

    @Override
    public Optional<AgentMemoryGenerationJob> claimNextPending(String workerId, Duration leaseDuration) {
        AgentMemoryGenerationJobPO next = dao.selectNextPending();
        if (next == null) {
            return Optional.empty();
        }
        String lockExpiresAt = Instant.now().plus(leaseDuration).toString();
        int claimed = dao.claimJob(next.getJobId(), workerId, lockExpiresAt);
        if (claimed == 0) {
            return Optional.empty();
        }
        next.setStatus("RUNNING");
        next.setLockedBy(workerId);
        next.setLockExpiresAt(lockExpiresAt);
        return Optional.of(toEntity(next));
    }

    @Override
    public boolean transitionToSucceeded(String jobId, String lockedBy) {
        return dao.transitionToTerminal(jobId, "SUCCEEDED", lockedBy) > 0;
    }

    @Override
    public boolean transitionToSkipped(String jobId, String lockedBy) {
        return dao.transitionToTerminal(jobId, "SKIPPED", lockedBy) > 0;
    }

    @Override
    public boolean transitionToRetry(String jobId, int retryCount, Instant notBefore, String errorMsg) {
        return dao.transitionToRetry(jobId, retryCount, notBefore.toString(), errorMsg) > 0;
    }

    @Override
    public boolean transitionToFailed(String jobId, int retryCount, String errorMsg) {
        return dao.transitionToFailed(jobId, retryCount, errorMsg) > 0;
    }

    @Override
    public int recoverStaleJobs(Duration staleThreshold, int maxRetries) {
        String threshold = Instant.now().minus(staleThreshold).toString();
        return dao.recoverStaleJobs(threshold, maxRetries);
    }

    @Override
    public Optional<AgentMemoryGenerationJob> findBySourceRunId(String sourceRunId) {
        return Optional.ofNullable(dao.selectBySourceRunId(sourceRunId)).map(this::toEntity);
    }

    private AgentMemoryGenerationJobPO toPo(AgentMemoryGenerationJob job) {
        AgentMemoryGenerationJobPO po = new AgentMemoryGenerationJobPO();
        po.setJobId(job.getJobId());
        po.setSourceRunId(job.getSourceRunId());
        po.setWorkspaceKey(job.getWorkspaceKey());
        po.setConversationSummaryJson(job.getConversationSummaryJson() != null ? job.getConversationSummaryJson() : "{}");
        po.setStatus(job.getStatus().name());
        po.setNotBefore(job.getNotBefore() != null ? job.getNotBefore().toString() : Instant.now().toString());
        po.setLockedBy(job.getLockedBy());
        po.setLockExpiresAt(job.getLockExpiresAt() != null ? job.getLockExpiresAt().toString() : null);
        po.setRetryCount(job.getRetryCount());
        po.setErrorMessage(job.getErrorMsg());
        return po;
    }

    private AgentMemoryGenerationJob toEntity(AgentMemoryGenerationJobPO po) {
        return AgentMemoryGenerationJob.builder()
                .jobId(po.getJobId())
                .sourceRunId(po.getSourceRunId())
                .workspaceKey(po.getWorkspaceKey())
                .conversationSummaryJson(po.getConversationSummaryJson())
                .status(MemoryGenerationJobStatus.valueOf(po.getStatus()))
                .notBefore(po.getNotBefore() != null ? Instant.from(FMT.parse(po.getNotBefore())) : null)
                .lockedBy(po.getLockedBy())
                .lockExpiresAt(po.getLockExpiresAt() != null ? Instant.from(FMT.parse(po.getLockExpiresAt())) : null)
                .retryCount(po.getRetryCount() != null ? po.getRetryCount() : 0)
                .errorMsg(po.getErrorMessage())
                .createdAt(po.getCreatedAt() != null ? Instant.from(FMT.parse(po.getCreatedAt())) : null)
                .updatedAt(po.getUpdatedAt() != null ? Instant.from(FMT.parse(po.getUpdatedAt())) : null)
                .build();
    }
}
