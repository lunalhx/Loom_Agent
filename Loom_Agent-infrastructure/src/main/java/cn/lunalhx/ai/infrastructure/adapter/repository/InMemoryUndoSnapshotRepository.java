package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.agent.adapter.port.UndoSnapshotRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentUndoSnapshot;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryStoreProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.UndoSnapshotStatus;
import com.google.common.cache.CacheBuilder;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryUndoSnapshotRepository implements UndoSnapshotRepository {

    private final ConcurrentMap<String, AgentUndoSnapshot> snapshots;
    private final AtomicLong idCounter = new AtomicLong(0);

    public InMemoryUndoSnapshotRepository() {
        this.snapshots = new java.util.concurrent.ConcurrentHashMap<>();
    }

    public InMemoryUndoSnapshotRepository(MemoryStoreProperties props) {
        this.snapshots = CacheBuilder.newBuilder()
                .maximumSize(props.getMaxRuns())
                .expireAfterAccess(props.getTtlSeconds(), TimeUnit.SECONDS)
                .<String, AgentUndoSnapshot>build()
                .asMap();
    }

    @Override
    public AgentUndoSnapshot save(AgentUndoSnapshot snapshot) {
        Instant now = Instant.now();
        if (snapshot.getCreatedAt() == null) {
            snapshot.setCreatedAt(now);
        }
        if (snapshot.getVersion() == null) {
            snapshot.setVersion(0L);
        }
        if (snapshot.getSnapshotId() == null) {
            snapshot.setSnapshotId("undo-" + idCounter.incrementAndGet());
        }
        snapshots.put(snapshot.getSnapshotId(), snapshot);
        return snapshot;
    }

    @Override
    public Optional<AgentUndoSnapshot> findBySnapshotId(String snapshotId) {
        if (StringUtils.isBlank(snapshotId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshots.get(snapshotId));
    }

    @Override
    public Optional<AgentUndoSnapshot> findByRunId(String runId) {
        if (StringUtils.isBlank(runId)) {
            return Optional.empty();
        }
        return snapshots.values().stream()
                .filter(s -> runId.equals(s.getRunId()))
                .findFirst();
    }

    @Override
    public Optional<AgentUndoSnapshot> findLatestByWorkspace(String workspace) {
        if (StringUtils.isBlank(workspace)) {
            return Optional.empty();
        }
        return snapshots.values().stream()
                .filter(s -> workspace.equals(s.getWorkspace()))
                .max(Comparator.comparing(s -> s.getCreatedAt() != null ? s.getCreatedAt() : Instant.EPOCH));
    }

    @Override
    public Optional<AgentUndoSnapshot> findLatestByConversationId(String conversationId) {
        if (StringUtils.isBlank(conversationId)) {
            return Optional.empty();
        }
        return snapshots.values().stream()
                .filter(s -> conversationId.equals(s.getConversationId()))
                .max(Comparator.comparing(s -> s.getCreatedAt() != null ? s.getCreatedAt() : Instant.EPOCH));
    }

    @Override
    public boolean updateStatus(String snapshotId, UndoSnapshotStatus expected, UndoSnapshotStatus next) {
        AgentUndoSnapshot snapshot = snapshots.get(snapshotId);
        if (snapshot == null || snapshot.getStatus() != expected) {
            return false;
        }
        snapshot.setStatus(next);
        snapshot.setVersion(snapshot.getVersion() + 1);
        return true;
    }

    @Override
    public List<AgentUndoSnapshot> findExpired(Instant now) {
        List<AgentUndoSnapshot> expired = new ArrayList<>();
        for (AgentUndoSnapshot snapshot : snapshots.values()) {
            UndoSnapshotStatus status = snapshot.getStatus();
            if ((status == UndoSnapshotStatus.READY
                    || status == UndoSnapshotStatus.OPEN
                    || status == UndoSnapshotStatus.SUSPENDED)
                    && snapshot.getExpiresAt() != null
                    && snapshot.getExpiresAt().isBefore(now)) {
                expired.add(snapshot);
            }
        }
        return expired;
    }

    @Override
    public int expireByStatus(String snapshotId, UndoSnapshotStatus from, UndoSnapshotStatus to) {
        AgentUndoSnapshot snapshot = snapshots.get(snapshotId);
        if (snapshot == null || snapshot.getStatus() != from) {
            return 0;
        }
        snapshot.setStatus(to);
        snapshot.setVersion(snapshot.getVersion() + 1);
        return 1;
    }
}
