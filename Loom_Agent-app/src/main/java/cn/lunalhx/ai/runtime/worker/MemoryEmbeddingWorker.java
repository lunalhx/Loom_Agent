package cn.lunalhx.ai.runtime.worker;

import cn.lunalhx.ai.config.MemoryProperties;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryVectorIndex;
import cn.lunalhx.ai.domain.memory.adapter.port.MemoryEmbeddingGateway;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;
import cn.lunalhx.ai.domain.memory.model.valobj.EmbeddingVector;
import cn.lunalhx.ai.infrastructure.adapter.embedding.OpenAiCompatibleEmbeddingGateway;
import cn.lunalhx.ai.infrastructure.dao.AgentMemoryEmbeddingJobDao;
import cn.lunalhx.ai.infrastructure.dao.po.AgentMemoryEmbeddingJobPO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnExpression("${loom.agent.long-term-memory.enabled:false} && ${loom.agent.long-term-memory.vector.enabled:true}")
public class MemoryEmbeddingWorker {

    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddingWorker.class);
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_BACKOFF_SECONDS = 30;

    private final DataSource dataSource;
    private final AgentMemoryRepository memoryRepository;
    private final AgentMemoryVectorIndex vectorIndex;
    private final MemoryEmbeddingGateway embeddingGateway;
    private final MemoryProperties.VectorConfig config;
    private final AgentMemoryEmbeddingJobDao embeddingJobDao;

    public MemoryEmbeddingWorker(AgentMemoryEmbeddingJobDao embeddingJobDao,
                                  DataSource dataSource,
                                  AgentMemoryRepository memoryRepository,
                                  AgentMemoryVectorIndex vectorIndex,
                                  MemoryEmbeddingGateway embeddingGateway,
                                  MemoryProperties memoryProperties) {
        this.dataSource = dataSource;
        this.memoryRepository = memoryRepository;
        this.vectorIndex = vectorIndex;
        this.embeddingGateway = embeddingGateway;
        this.config = memoryProperties.getVector();
        this.embeddingJobDao = embeddingJobDao;
    }

    @Scheduled(fixedDelayString = "${loom.agent.long-term-memory.vector.worker-poll-interval-ms:5000}")
    public void processJobs() {
        if (!vectorIndex.available()) {
            return;
        }

        AgentMemoryEmbeddingJobPO po = embeddingJobDao.selectNextPending();
        if (po == null) {
            return;
        }

        if (embeddingJobDao.claimJob(po.getJobId()) == 0) {
            return;
        }

        try {
            processJob(po.getJobId(), po.getMemoryId(), po.getAction());
        } catch (Exception e) {
            handleFailure(po.getJobId(), po.getMemoryId(), po.getRetryCount(), e);
        }
    }

    private void processJob(String jobId, String memoryId, String action) throws Exception {
        if ("UPSERT".equals(action)) {
            processUpsert(jobId, memoryId);
        } else if ("DELETE".equals(action)) {
            processDelete(jobId, memoryId);
        } else {
            markFailed(jobId, 0, "unknown action: " + action);
        }
    }

    private void processUpsert(String jobId, String memoryId) throws Exception {
        Optional<AgentMemory> memOpt = memoryRepository.findById(memoryId);
        if (memOpt.isEmpty()) {
            markFailed(jobId, MAX_RETRIES, "memory not found: " + memoryId);
            return;
        }

        AgentMemory memory = memOpt.get();
        String text = OpenAiCompatibleEmbeddingGateway.formatEmbeddingText(
                memory.getType().name(), memory.getTitle(), memory.getSummary(), memory.getBody());

        EmbeddingVector vector = embeddingGateway.embed(text);

        long memoryRowId = upsertVectorRef(memory, vector);
        vectorIndex.upsert(memoryRowId, vector, memory.getWorkspaceKey(),
                memory.getStatus().name(), memory.getType().name(), memory.getImportance());

        markSucceeded(jobId);
        log.info("Embedding upsert succeeded: jobId={}, memoryId={}, rowId={}", jobId, memoryId, memoryRowId);
    }

    private void processDelete(String jobId, String memoryId) throws Exception {
        Long memoryRowId = findMemoryRowId(memoryId);
        if (memoryRowId == null) {
            markFailed(jobId, MAX_RETRIES, "vector ref not found for memory: " + memoryId);
            return;
        }

        deleteVectorRef(memoryId);
        vectorIndex.delete(memoryRowId);

        markSucceeded(jobId);
        log.info("Embedding delete succeeded: jobId={}, memoryId={}, rowId={}", jobId, memoryId, memoryRowId);
    }

    private long upsertVectorRef(AgentMemory memory, EmbeddingVector vector) throws SQLException {
        String sql = "INSERT OR REPLACE INTO agent_memory_vector_ref "
                + "(memory_id, workspace_key, content_hash, embedding_model, embedding_dimension, embedded_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, memory.getMemoryId());
            ps.setString(2, memory.getWorkspaceKey());
            ps.setString(3, memory.getContentHash() != null ? memory.getContentHash() : "");
            ps.setString(4, vector.model());
            ps.setInt(5, vector.dimensions());
            ps.setString(6, DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }

        Long rowId = findMemoryRowId(memory.getMemoryId());
        if (rowId != null) {
            return rowId;
        }
        throw new RuntimeException("failed to obtain memory_rowid after upsert for memory: " + memory.getMemoryId());
    }

    private Long findMemoryRowId(String memoryId) throws SQLException {
        String sql = "SELECT memory_rowid FROM agent_memory_vector_ref WHERE memory_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, memoryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("memory_rowid");
                }
            }
        }
        return null;
    }

    private void deleteVectorRef(String memoryId) throws SQLException {
        String sql = "DELETE FROM agent_memory_vector_ref WHERE memory_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, memoryId);
            ps.executeUpdate();
        }
    }

    private void markSucceeded(String jobId) {
        embeddingJobDao.markSucceeded(jobId);
    }

    private void markFailed(String jobId, int forceRetryCount, String errorMessage) {
        embeddingJobDao.markFailed(jobId, forceRetryCount,
                truncate(errorMessage, 1000));
    }

    private void handleFailure(String jobId, String memoryId, int retryCount, Exception e) {
        int newRetryCount = retryCount + 1;
        if (newRetryCount > MAX_RETRIES) {
            embeddingJobDao.markFailed(jobId, newRetryCount,
                    truncate(e.getMessage(), 1000));
            log.warn("Embedding job failed permanently after {} retries: jobId={}, memoryId={}, error={}",
                    newRetryCount, jobId, memoryId, e.getMessage());
        } else {
            String notBefore = DateTimeFormatter.ISO_INSTANT.format(
                    Instant.now().plus(Duration.ofSeconds(RETRY_BACKOFF_SECONDS)));
            embeddingJobDao.markRetry(jobId, newRetryCount, notBefore,
                    truncate(e.getMessage(), 1000));
            log.warn("Embedding job will retry: jobId={}, memoryId={}, retry={}/{}, error={}",
                    jobId, memoryId, newRetryCount, MAX_RETRIES, e.getMessage());
        }
    }

    public void reindexAll() {
        if (!vectorIndex.available()) {
            log.warn("Vector index not available, skipping reindexAll");
            return;
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM agent_memory_vector_ref");
        } catch (SQLException e) {
            log.warn("Failed to clear agent_memory_vector_ref: {}", e.getMessage());
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM agent_memory_vec");
            log.info("Cleared agent_memory_vec for reindex");
        } catch (SQLException e) {
            log.warn("Failed to clear agent_memory_vec (table may not exist): {}", e.getMessage());
        }

        String selectMemSql = "SELECT memory_id FROM agent_memory WHERE status = 'ACTIVE'";
        int enqueued = 0;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement psMem = conn.prepareStatement(selectMemSql)) {
            try (ResultSet rs = psMem.executeQuery()) {
                while (rs.next()) {
                    String memoryId = rs.getString("memory_id");
                    String jobId = UUID.randomUUID().toString();
                    embeddingJobDao.insertOrIgnore(jobId, memoryId, "UPSERT");
                    enqueued++;
                }
            }
        } catch (SQLException e) {
            log.error("Reindex failed: {}", e.getMessage());
            throw new RuntimeException("reindex failed", e);
        }

        log.info("Reindex complete: enqueued {} active memories for embedding", enqueued);
        log.info("Cleared agent_memory_vector_ref for reindex");
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
