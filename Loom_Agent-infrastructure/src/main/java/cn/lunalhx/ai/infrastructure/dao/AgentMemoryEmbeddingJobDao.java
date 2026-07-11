package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.AgentMemoryEmbeddingJobPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentMemoryEmbeddingJobDao {

    AgentMemoryEmbeddingJobPO selectNextPending();

    int claimJob(@Param("jobId") String jobId);

    int markSucceeded(@Param("jobId") String jobId);

    int markFailed(@Param("jobId") String jobId,
                   @Param("retryCount") int retryCount,
                   @Param("errorMessage") String errorMessage);

    int markRetry(@Param("jobId") String jobId,
                  @Param("retryCount") int retryCount,
                  @Param("notBefore") String notBefore,
                  @Param("errorMessage") String errorMessage);

    int insertOrIgnore(@Param("jobId") String jobId,
                       @Param("memoryId") String memoryId,
                       @Param("action") String action);
}
