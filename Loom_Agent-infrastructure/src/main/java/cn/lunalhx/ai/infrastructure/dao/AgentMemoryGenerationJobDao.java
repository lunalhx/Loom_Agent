package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.AgentMemoryGenerationJobPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentMemoryGenerationJobDao {

    int insertOrIgnore(AgentMemoryGenerationJobPO job);

    AgentMemoryGenerationJobPO selectNextPending();

    int claimJob(String jobId, String lockedBy, String lockExpiresAt);

    int updateStatus(String jobId, String status, String errorMessage, int retryCount);

    int recoverStaleJobs(String staleThreshold);

    AgentMemoryGenerationJobPO selectBySourceRunId(String sourceRunId);

    int cancelBySourceRunIds(@Param("sourceRunIds") List<String> sourceRunIds);

    int deleteBySourceRunIds(@Param("sourceRunIds") List<String> sourceRunIds);
}
