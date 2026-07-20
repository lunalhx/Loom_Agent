package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.tool.model.ExtensionsConfig;
import cn.lunalhx.ai.infrastructure.mcp.ExtensionsConfigLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class ExtensionsConfigRegistry {

    private final ExtensionsConfigLoader loader;
    private final ObjectMapper mapper;
    private final AtomicReference<ExtensionsConfigSnapshot> current = new AtomicReference<>();

    public ExtensionsConfigRegistry(ExtensionsConfigLoader loader, ObjectMapper mapper) {
        this.loader = loader;
        this.mapper = mapper;
    }

    public void initialize() {
        publish(loader.load(), 1);
    }

    public synchronized void reload(Consumer<ExtensionsConfig> beforeCommit) {
        ExtensionsConfig replacement = loader.load();
        beforeCommit.accept(copy(replacement));
        long version = current.get() == null ? 1 : current.get().version() + 1;
        publish(replacement, version);
    }

    public ExtensionsConfigSnapshot capture() {
        ExtensionsConfigSnapshot snapshot = current.get();
        if (snapshot == null) {
            throw new IllegalStateException("Extensions configuration has not been initialized");
        }
        return new ExtensionsConfigSnapshot(snapshot.version(), snapshot.fingerprint(), snapshot.loadedAt(),
                copy(snapshot.config()));
    }

    private void publish(ExtensionsConfig config, long version) {
        ExtensionsConfig stored = copy(config);
        String fingerprint;
        try {
            fingerprint = DigestUtils.sha256Hex(mapper.writeValueAsBytes(stored));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fingerprint extensions configuration", e);
        }
        current.set(new ExtensionsConfigSnapshot(version, fingerprint, Instant.now(), stored));
    }

    private ExtensionsConfig copy(ExtensionsConfig source) {
        return mapper.convertValue(source, ExtensionsConfig.class);
    }
}
