package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.AgentMemoryGenerationJobPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentMemoryGenerationJobDao {

    int insertOrIgnore(AgentMemoryGenerationJobPO job);

    AgentMemoryGenerationJobPO selectNextPending();

    int claimJob(String jobId, String lockedBy, String lockExpiresAt);

    int updateStatus(String jobId, String status, String errorMessage, int retryCount);

    int recoverStaleJobs(String staleThreshold);

    AgentMemoryGenerationJobPO selectBySourceRunId(String sourceRunId);
}
