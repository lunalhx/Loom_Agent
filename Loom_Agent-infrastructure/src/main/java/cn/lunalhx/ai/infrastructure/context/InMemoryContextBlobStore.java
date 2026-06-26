package cn.lunalhx.ai.infrastructure.context;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryStoreProperties;
import com.google.common.cache.CacheBuilder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class InMemoryContextBlobStore implements ContextBlobStore {

    private final Map<String, String> blobs;

    public InMemoryContextBlobStore() {
        this.blobs = new ConcurrentHashMap<>();
    }

    public InMemoryContextBlobStore(MemoryStoreProperties props) {
        this.blobs = CacheBuilder.newBuilder()
                .maximumSize(props.getMaxContextBlobs())
                .expireAfterAccess(props.getTtlSeconds(), TimeUnit.SECONDS)
                .<String, String>build()
                .asMap();
    }

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
