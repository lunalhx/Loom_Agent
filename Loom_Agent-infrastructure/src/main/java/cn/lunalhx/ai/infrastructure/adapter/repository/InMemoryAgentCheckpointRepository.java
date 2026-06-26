package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryStoreProperties;
import com.google.common.cache.CacheBuilder;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class InMemoryAgentCheckpointRepository implements AgentCheckpointRepository {

    private final ConcurrentMap<String, CopyOnWriteArrayList<AgentCheckpoint>> checkpoints;
    private final int maxCheckpointsPerRun;

    public InMemoryAgentCheckpointRepository() {
        this.checkpoints = new ConcurrentHashMap<>();
        this.maxCheckpointsPerRun = Integer.MAX_VALUE;
    }

    public InMemoryAgentCheckpointRepository(MemoryStoreProperties props) {
        this.checkpoints = CacheBuilder.newBuilder()
                .maximumSize(props.getMaxCheckpointRuns())
                .expireAfterAccess(props.getTtlSeconds(), TimeUnit.SECONDS)
                .<String, CopyOnWriteArrayList<AgentCheckpoint>>build()
                .asMap();
        this.maxCheckpointsPerRun = props.getMaxCheckpointsPerRun();
    }

    @Override
    public AgentCheckpoint save(AgentCheckpoint checkpoint) {
        CopyOnWriteArrayList<AgentCheckpoint> runCheckpoints = checkpoints.computeIfAbsent(
                checkpoint.getRunId(), key -> new CopyOnWriteArrayList<>());
        while (runCheckpoints.size() >= maxCheckpointsPerRun) {
            runCheckpoints.remove(0);
        }
        long nextVersion = runCheckpoints.stream()
                .map(AgentCheckpoint::getVersion)
                .filter(version -> version != null)
                .max(Long::compareTo)
                .orElse(0L) + 1L;
        checkpoint.setVersion(nextVersion);
        checkpoint.setCreatedAt(Instant.now());
        runCheckpoints.add(checkpoint);
        return checkpoint;
    }

    @Override
    public Optional<AgentCheckpoint> latest(String runId) {
        if (StringUtils.isBlank(runId)) {
            return Optional.empty();
        }
        return checkpoints.getOrDefault(runId, new CopyOnWriteArrayList<>()).stream()
                .max(Comparator.comparing(AgentCheckpoint::getVersion));
    }

}
