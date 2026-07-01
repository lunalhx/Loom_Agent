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

    int transitionToTerminal(@Param("jobId") String jobId,
                             @Param("status") String status,
                             @Param("lockedBy") String lockedBy);

    int transitionToRetry(@Param("jobId") String jobId,
                          @Param("retryCount") int retryCount,
                          @Param("notBefore") String notBefore,
                          @Param("errorMessage") String errorMessage);

    int transitionToFailed(@Param("jobId") String jobId,
                           @Param("retryCount") int retryCount,
                           @Param("errorMessage") String errorMessage);

    int recoverStaleJobs(@Param("staleThreshold") String staleThreshold,
                         @Param("maxRetries") int maxRetries);

    AgentMemoryGenerationJobPO selectBySourceRunId(String sourceRunId);

    int cancelBySourceRunIds(@Param("sourceRunIds") List<String> sourceRunIds);

    int deleteBySourceRunIds(@Param("sourceRunIds") List<String> sourceRunIds);
}
