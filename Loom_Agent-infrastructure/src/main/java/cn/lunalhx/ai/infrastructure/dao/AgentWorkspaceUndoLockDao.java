package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.AgentWorkspaceUndoLockPO;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;

@Mapper
public interface AgentWorkspaceUndoLockDao {

    int tryAcquire(AgentWorkspaceUndoLockPO po);

    int deleteOwned(@org.apache.ibatis.annotations.Param("workspace") String workspace,
                    @org.apache.ibatis.annotations.Param("runId") String runId);

    AgentWorkspaceUndoLockPO selectByWorkspace(String workspace);

    int deleteStaleBefore(Instant threshold);
}
