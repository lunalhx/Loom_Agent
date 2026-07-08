package cn.lunalhx.ai.domain.memory.adapter.port;

import cn.lunalhx.ai.domain.memory.model.valobj.EmbeddingVector;

import java.util.List;

public interface MemoryEmbeddingGateway {

    EmbeddingVector embed(String text);

    List<EmbeddingVector> embedBatch(List<String> texts);
}
