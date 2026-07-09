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

    boolean updateUsage(String memoryId, long expectedVersion);

    int countActive(String workspaceKey);

    boolean updateStatus(String memoryId, MemoryStatus status, long expectedVersion);

    List<AgentMemory> findByContentHash(String workspaceKey, String contentHash);

    List<AgentMemory> findBySourceRunId(String sourceRunId);

    List<AgentMemory> findExpiredActive(String workspaceKey, int unusedDays, int minImportance, int limit);

    Optional<AgentMemory> findWeakestCandidate(String workspaceKey);

    int batchUpdateStatus(List<String> memoryIds, MemoryStatus status);

    /**
     * Find active, non-pinned memories unused for longer than the given days,
     * across ALL workspaces. Used by the scheduled archive worker.
     */
    List<AgentMemory> findExpiredActiveAll(int unusedDays, int minImportance, int limit);
}
