package cn.lunalhx.ai.domain.memory.model.valobj;

public record EmbeddingVector(float[] values, String model, int dimensions) {

    public EmbeddingVector {
        if (values == null || values.length != dimensions) {
            throw new IllegalArgumentException(
                    "embedding dimension mismatch: expected " + dimensions + ", got " + (values != null ? values.length : 0));
        }
    }
}
