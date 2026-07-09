package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.AgentMemoryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentMemoryDao {

    int insert(AgentMemoryPO memory);

    int update(AgentMemoryPO memory);

    AgentMemoryPO selectById(String memoryId);

    List<AgentMemoryPO> selectActive(String workspaceKey, int limit);

    List<AgentMemoryPO> selectPinned(String workspaceKey, int limit);

    List<AgentMemoryPO> searchByKeywords(String workspaceKey, String searchTerm, int limit);

    int updateUsage(@Param("memoryId") String memoryId, @Param("expectedVersion") long expectedVersion);

    int countActive(String workspaceKey);

    int updateStatus(String memoryId, String status, long expectedVersion);

    List<AgentMemoryPO> selectByContentHash(String workspaceKey, String contentHash);

    List<AgentMemoryPO> selectBySourceRunId(String sourceRunId);

    List<AgentMemoryPO> selectExpiredActive(
            @Param("workspaceKey") String workspaceKey,
            @Param("unusedDays") int unusedDays,
            @Param("minImportance") int minImportance,
            @Param("limit") int limit);

    AgentMemoryPO selectWeakestCandidate(@Param("workspaceKey") String workspaceKey);

    int batchUpdateStatus(
            @Param("memoryIds") List<String> memoryIds,
            @Param("status") String status);

    List<AgentMemoryPO> selectExpiredActiveAll(
            @Param("unusedDays") int unusedDays,
            @Param("minImportance") int minImportance,
            @Param("limit") int limit);
}
