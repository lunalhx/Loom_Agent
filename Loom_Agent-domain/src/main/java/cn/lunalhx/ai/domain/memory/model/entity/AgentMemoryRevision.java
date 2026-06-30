package cn.lunalhx.ai.domain.memory.model.entity;

import cn.lunalhx.ai.domain.memory.model.valobj.MemorySourceType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class AgentMemoryRevision {
    private Long id;
    private String memoryId;
    private int version;
    private String snapshotJson;
    private MemorySourceType sourceType;
    private String sourceRunId;
    private Instant createdAt;
}
