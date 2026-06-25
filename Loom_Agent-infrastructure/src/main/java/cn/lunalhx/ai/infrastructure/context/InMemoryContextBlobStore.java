package cn.lunalhx.ai.infrastructure.context;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryContextBlobStore implements ContextBlobStore {

    private final Map<String, String> blobs = new ConcurrentHashMap<>();

    @Override
    public String write(String rootRunId, String artifactId, String content) {
        String uri = "memory://" + rootRunId + "/" + artifactId;
        blobs.put(uri, content);
        return uri;
    }

    @Override
    public String read(String storageUri) {
        return blobs.getOrDefault(storageUri, "");
    }

}
