package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.AgentMemoryPO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AgentMemoryDao {

    int insert(AgentMemoryPO memory);

    int update(AgentMemoryPO memory);

    AgentMemoryPO selectById(String memoryId);

    List<AgentMemoryPO> selectActive(String workspaceKey, int limit);

    List<AgentMemoryPO> selectPinned(String workspaceKey, int limit);

    List<AgentMemoryPO> searchByKeywords(String workspaceKey, String searchTerm, int limit);

    int updateUsage(String memoryId, long newVersion);

    int countActive(String workspaceKey);

    int updateStatus(String memoryId, String status, long expectedVersion);
}
