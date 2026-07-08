package cn.lunalhx.ai.domain.memory.adapter.port;

import cn.lunalhx.ai.domain.memory.model.valobj.EmbeddingVector;
import cn.lunalhx.ai.domain.memory.model.valobj.ScoredMemoryId;

import java.util.List;

public interface AgentMemoryVectorIndex {

    void upsert(long memoryRowId, EmbeddingVector vector, String workspaceKey, String status, String type, int importance);

    void delete(long memoryRowId);

    List<ScoredMemoryId> search(String workspaceKey, EmbeddingVector query, int k);

    boolean available();

    void createIfNeeded();
}
