package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.BackgroundShellTaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BackgroundShellTaskDao {

    int upsert(BackgroundShellTaskPO po);

    BackgroundShellTaskPO selectByTaskId(@Param("taskId") String taskId);

    List<BackgroundShellTaskPO> selectByRunId(@Param("runId") String runId);

    List<BackgroundShellTaskPO> selectRunningByRunId(@Param("runId") String runId);

    List<BackgroundShellTaskPO> selectStaleRunning(@Param("statuses") List<String> statuses);

    int markNotified(@Param("taskId") String taskId);

    void deleteByRunId(@Param("runId") String runId);

    void deleteByConversationId(@Param("conversationId") String conversationId);

}
