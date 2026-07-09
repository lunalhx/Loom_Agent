package cn.lunalhx.ai.domain.memory.model.valobj;

import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;

/**
 * Wraps an AgentMemory with its composite search score and recall source.
 */
public record MemorySearchHit(
    AgentMemory memory,
    double comprehensiveScore,
    String recallSource  // "VECTOR", "KEYWORD", "PINNED"
) {
    public static final String SOURCE_VECTOR = "VECTOR";
    public static final String SOURCE_KEYWORD = "KEYWORD";
    public static final String SOURCE_PINNED = "PINNED";
}
