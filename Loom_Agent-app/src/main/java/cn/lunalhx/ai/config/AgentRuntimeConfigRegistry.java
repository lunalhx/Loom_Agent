package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunConfig;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRuntimeConfigSource;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class AgentRuntimeConfigRegistry implements AgentRuntimeConfigSource {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeConfigRegistry.class);
    private static final Set<String> ROOT_KEYS = Set.of("agent", "ai");

    private final Path path;
    private final AgentRuntimeProperties liveAgent;
    private final ModelRuntimeProperties liveModel;
    private final ObjectMapper mapper;
    private final AtomicReference<AgentRuntimeConfigSnapshot> current = new AtomicReference<>();

    public AgentRuntimeConfigRegistry(Path path,
                                      AgentRuntimeProperties liveAgent,
                                      ModelRuntimeProperties liveModel) {
        this.path = path.toAbsolutePath().normalize();
        this.liveAgent = liveAgent;
        this.liveModel = liveModel;
        this.mapper = new ObjectMapper(new YAMLFactory())
                .findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public void initialize() {
        if (Files.isRegularFile(path)) {
            reload();
            return;
        }
        current.set(new AgentRuntimeConfigSnapshot(0, "startup", Instant.now(),
                copy(liveAgent, AgentRuntimeProperties.class), copy(liveModel, ModelRuntimeProperties.class)));
    }

    public synchronized void reload() {
        try {
            byte[] raw = Files.readAllBytes(path);
            JsonNode root = mapper.readTree(raw);
            JsonNode config = unwrap(root);
            validateRoot(config);
            AgentRuntimeProperties nextAgent = copy(liveAgent, AgentRuntimeProperties.class);
            ModelRuntimeProperties nextModel = copy(liveModel, ModelRuntimeProperties.class);
            if (config.has("agent")) {
                mapper.readerForUpdating(nextAgent).readValue(config.get("agent"));
            }
            if (config.has("ai")) {
                JsonNode ai = config.get("ai");
                if (ai.has("provider") || ai.has("providers") || ai.has("connect-timeout-ms")) {
                    throw new IllegalStateException("loom.ai provider, providers, credentials, and client construction settings are startup-only");
                }
                mapper.readerForUpdating(nextModel).readValue(ai);
            }
            RuntimeConfigValidators.validate(nextAgent, nextModel);
            long version = current.get() == null ? 1 : current.get().version() + 1;
            String fingerprint = DigestUtils.sha256Hex(raw);
            AgentRuntimeProperties snapshotAgent = copy(nextAgent, AgentRuntimeProperties.class);
            ModelRuntimeProperties snapshotModel = copy(nextModel, ModelRuntimeProperties.class);
            liveAgent.replaceFrom(copy(nextAgent, AgentRuntimeProperties.class));
            liveModel.replaceFrom(copy(nextModel, ModelRuntimeProperties.class));
            current.set(new AgentRuntimeConfigSnapshot(version, fingerprint, Instant.now(),
                    snapshotAgent, snapshotModel));
            log.info("Runtime configuration loaded: version={} fingerprint={}", version,
                    fingerprint.substring(0, 12));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot parse runtime configuration " + path + ": " + e.getMessage(), e);
        }
    }

    public AgentRuntimeConfigSnapshot capture() {
        return current.get();
    }

    @Override
    public AgentRunConfig captureRunConfig() {
        AgentRuntimeConfigSnapshot snapshot = current.get();
        if (snapshot == null) {
            throw new IllegalStateException("Runtime configuration has not been initialized");
        }
        return new AgentRunConfig(snapshot.version(), snapshot.fingerprint(), snapshot.loadedAt(),
                copy(snapshot.agent(), AgentRuntimeProperties.class),
                copy(snapshot.model(), ModelRuntimeProperties.class));
    }

    public Path path() {
        return path;
    }

    private JsonNode unwrap(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("Runtime configuration must be a YAML object");
        }
        if (root.has("loom")) {
            if (root.size() != 1 || !root.get("loom").isObject()) {
                throw new IllegalStateException("Only the loom root key is allowed in runtime configuration");
            }
            return root.get("loom");
        }
        return root;
    }

    private void validateRoot(JsonNode root) {
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!ROOT_KEYS.contains(name)) {
                throw new IllegalStateException("Unknown runtime configuration path: loom." + name);
            }
        }
    }

    private <T> T copy(T source, Class<T> type) {
        return mapper.convertValue(source, type);
    }
}
