package cn.lunalhx.ai.domain.memory.model.entity;

import cn.lunalhx.ai.domain.memory.model.valobj.MemorySourceType;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryStatus;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class AgentMemory {
    private String memoryId;
    private String workspaceKey;
    private String workspacePath;
    private MemoryType type;
    private String title;
    private String summary;
    private String body;
    private MemoryStatus status;
    private boolean pinned;
    private int importance;
    private MemorySourceType sourceType;
    private String sourceRunId;
    private String contentHash;
    private long version;
    private int usageCount;
    private Instant lastUsedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
