package cn.lunalhx.ai.domain.agent.adapter.port.context;

public interface ContextBlobStore {

    String write(String rootRunId, String artifactId, String content);

    String read(String storageUri);

    void delete(String storageUri);
}
