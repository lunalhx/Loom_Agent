package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.AgentUndoSnapshotPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface AgentUndoSnapshotDao {

    int insert(AgentUndoSnapshotPO po);

    int upsert(AgentUndoSnapshotPO po);

    AgentUndoSnapshotPO selectBySnapshotId(String snapshotId);

    AgentUndoSnapshotPO selectByRunId(String runId);

    AgentUndoSnapshotPO selectLatestByWorkspace(String workspace);

    AgentUndoSnapshotPO selectLatestByConversationId(String conversationId);

    int updateStatus(@Param("snapshotId") String snapshotId,
                     @Param("expected") String expected,
                     @Param("next") String next,
                     @Param("expectedVersion") Long expectedVersion);

    List<AgentUndoSnapshotPO> selectExpired(@Param("now") Instant now);

    int expireByStatus(@Param("snapshotId") String snapshotId,
                       @Param("expected") String expected,
                       @Param("next") String next,
                       @Param("expectedVersion") Long expectedVersion);
}
