package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryAgentCheckpointRepository implements AgentCheckpointRepository {

    private final ConcurrentMap<String, CopyOnWriteArrayList<AgentCheckpoint>> checkpoints = new ConcurrentHashMap<>();

    @Override
    public AgentCheckpoint save(AgentCheckpoint checkpoint) {
        CopyOnWriteArrayList<AgentCheckpoint> runCheckpoints = checkpoints.computeIfAbsent(
                checkpoint.getRunId(), key -> new CopyOnWriteArrayList<>());
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
