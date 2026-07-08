package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryVectorIndex;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class IndexingAgentMemoryRepository implements AgentMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(IndexingAgentMemoryRepository.class);

    private final AgentMemoryRepository delegate;
    private final AgentMemoryVectorIndex vectorIndex;
    private final DataSource dataSource;

    public IndexingAgentMemoryRepository(AgentMemoryRepository delegate,
                                          AgentMemoryVectorIndex vectorIndex,
                                          DataSource dataSource) {
        this.delegate = delegate;
        this.vectorIndex = vectorIndex;
        this.dataSource = dataSource;
    }

    @Override
    public AgentMemory save(AgentMemory memory) {
        AgentMemory saved = delegate.save(memory);
        enqueueUpsert(saved);
        return saved;
    }

    @Override
    public boolean updateStatus(String memoryId, MemoryStatus status, long expectedVersion) {
        boolean ok = delegate.updateStatus(memoryId, status, expectedVersion);
        if (ok && vectorIndex.available()) {
            if (status == MemoryStatus.ACTIVE) {
                Optional<AgentMemory> mem = delegate.findById(memoryId);
                mem.ifPresent(this::enqueueUpsert);
            } else if (status == MemoryStatus.ARCHIVED || status == MemoryStatus.DELETED) {
                enqueueDelete(memoryId);
            }
        }
        return ok;
    }

    public void backfill(String workspaceKey) {
        if (!vectorIndex.available()) return;
        List<AgentMemory> active = delegate.findActive(workspaceKey, 200);
        for (AgentMemory m : active) {
            enqueueUpsert(m);
        }
    }

    private void enqueueUpsert(AgentMemory memory) {
        if (!vectorIndex.available()) return;
        String jobId = UUID.randomUUID().toString();
        String sql = "INSERT OR IGNORE INTO agent_memory_embedding_job(job_id, memory_id, action) VALUES (?, ?, 'UPSERT')";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobId);
            ps.setString(2, memory.getMemoryId());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("enqueue upsert skipped: {}", e.getMessage());
        }
    }

    private void enqueueDelete(String memoryId) {
        if (!vectorIndex.available()) return;
        String jobId = UUID.randomUUID().toString();
        String sql = "INSERT OR IGNORE INTO agent_memory_embedding_job(job_id, memory_id, action) VALUES (?, ?, 'DELETE')";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobId);
            ps.setString(2, memoryId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("enqueue delete skipped: {}", e.getMessage());
        }
    }

    @Override public Optional<AgentMemory> findById(String memoryId) { return delegate.findById(memoryId); }
    @Override public List<AgentMemory> findActive(String workspaceKey, int limit) { return delegate.findActive(workspaceKey, limit); }
    @Override public List<AgentMemory> findPinned(String workspaceKey, int limit) { return delegate.findPinned(workspaceKey, limit); }
    @Override public List<AgentMemory> searchByKeywords(String workspaceKey, List<String> keywords, int limit) { return delegate.searchByKeywords(workspaceKey, keywords, limit); }
    @Override public boolean updateUsage(String memoryId, long newVersion) { return delegate.updateUsage(memoryId, newVersion); }
    @Override public int countActive(String workspaceKey) { return delegate.countActive(workspaceKey); }
    @Override public List<AgentMemory> findByContentHash(String workspaceKey, String contentHash) { return delegate.findByContentHash(workspaceKey, contentHash); }
    @Override public List<AgentMemory> findBySourceRunId(String sourceRunId) { return delegate.findBySourceRunId(sourceRunId); }
}
