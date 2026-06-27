package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.AgentWorkspaceUndoLockPO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

@Mapper
public interface AgentWorkspaceUndoLockDao {

    int insert(AgentWorkspaceUndoLockPO po);

    int deleteByWorkspace(String workspace);

    AgentWorkspaceUndoLockPO selectByWorkspace(String workspace);

    int deleteStaleBefore(LocalDateTime threshold);
}
