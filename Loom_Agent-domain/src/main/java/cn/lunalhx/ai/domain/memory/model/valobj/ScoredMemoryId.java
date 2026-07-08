package cn.lunalhx.ai.domain.memory.model.valobj;

public record ScoredMemoryId(long memoryRowId, String memoryId, double distance, double score) {

    public ScoredMemoryId(long memoryRowId, String memoryId, double distance) {
        this(memoryRowId, memoryId, distance, 0.0);
    }
}
