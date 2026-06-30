package cn.lunalhx.ai.domain.memory.adapter.port;

import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryStatus;

import java.util.List;
import java.util.Optional;

public interface AgentMemoryRepository {

    AgentMemory save(AgentMemory memory);

    Optional<AgentMemory> findById(String memoryId);

    List<AgentMemory> findActive(String workspaceKey, int limit);

    List<AgentMemory> findPinned(String workspaceKey, int limit);

    List<AgentMemory> searchByKeywords(String workspaceKey, List<String> keywords, int limit);

    boolean updateUsage(String memoryId, long newVersion);

    int countActive(String workspaceKey);

    boolean updateStatus(String memoryId, MemoryStatus status, long expectedVersion);
}
