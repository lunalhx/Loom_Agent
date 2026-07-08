package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.memory.model.valobj.EmbeddingVector;
import org.junit.Test;

import static org.junit.Assert.*;

public class EmbeddingVectorTest {

    @Test
    public void shouldAcceptMatchingDimensions() {
        float[] values = new float[]{0.1f, 0.2f, 0.3f};
        EmbeddingVector vec = new EmbeddingVector(values, "test-model", 3);
        assertEquals(3, vec.dimensions());
        assertArrayEquals(values, vec.values(), 0.0f);
        assertEquals("test-model", vec.model());
    }

    @Test
    public void shouldRejectDimensionMismatch() {
        float[] values = new float[]{0.1f, 0.2f};
        try {
            new EmbeddingVector(values, "test-model", 3);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("dimension mismatch"));
        }
    }

    @Test
    public void shouldRejectNullValues() {
        try {
            new EmbeddingVector(null, "test-model", 3);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("dimension mismatch"));
        }
    }

    @Test
    public void shouldRejectZeroDimensions() {
        float[] values = new float[]{0.1f};
        try {
            new EmbeddingVector(values, "test-model", 0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("dimension mismatch"));
        }
    }

    @Test
    public void shouldCreateWithSingleValue() {
        float[] values = new float[]{0.5f};
        EmbeddingVector vec = new EmbeddingVector(values, "model-v1", 1);
        assertEquals(1, vec.dimensions());
        assertEquals(0.5f, vec.values()[0], 0.0001f);
    }
}
