package cn.lunalhx.ai.runtime.worker;

import cn.lunalhx.ai.config.MemoryProperties;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryVectorIndex;
import cn.lunalhx.ai.domain.memory.adapter.port.MemoryEmbeddingGateway;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;
import cn.lunalhx.ai.domain.memory.model.valobj.EmbeddingVector;
import cn.lunalhx.ai.infrastructure.adapter.embedding.OpenAiCompatibleEmbeddingGateway;
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

    public MemoryEmbeddingWorker(DataSource dataSource,
                                  AgentMemoryRepository memoryRepository,
                                  AgentMemoryVectorIndex vectorIndex,
                                  MemoryEmbeddingGateway embeddingGateway,
                                  MemoryProperties memoryProperties) {
        this.dataSource = dataSource;
        this.memoryRepository = memoryRepository;
        this.vectorIndex = vectorIndex;
        this.embeddingGateway = embeddingGateway;
        this.config = memoryProperties.getVector();
    }

    @Scheduled(fixedDelayString = "${loom.agent.long-term-memory.vector.worker-poll-interval-ms:5000}")
    public void processJobs() {
        if (!vectorIndex.available()) {
            return;
        }

        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String selectSql = "SELECT job_id, memory_id, action, retry_count FROM agent_memory_embedding_job "
                + "WHERE status = 'PENDING' AND not_before <= ? LIMIT 1";

        String jobId;
        String memoryId;
        String action;
        int retryCount;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                jobId = rs.getString("job_id");
                memoryId = rs.getString("memory_id");
                action = rs.getString("action");
                retryCount = rs.getInt("retry_count");

                if (!claimJob(conn, jobId)) {
                    return;
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to poll embedding jobs: {}", e.getMessage());
            return;
        }

        try {
            processJob(jobId, memoryId, action);
        } catch (Exception e) {
            handleFailure(jobId, memoryId, retryCount, e);
        }
    }

    private boolean claimJob(Connection conn, String jobId) throws SQLException {
        String updateSql = "UPDATE agent_memory_embedding_job "
                + "SET status = 'RUNNING', updated_at = ? WHERE job_id = ? AND status = 'PENDING'";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setString(1, DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
            ps.setString(2, jobId);
            return ps.executeUpdate() > 0;
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

    private void markSucceeded(String jobId) throws SQLException {
        String sql = "UPDATE agent_memory_embedding_job "
                + "SET status = 'SUCCEEDED', updated_at = ? WHERE job_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
            ps.setString(2, jobId);
            ps.executeUpdate();
        }
    }

    private void markFailed(String jobId, int forceRetryCount, String errorMessage) throws SQLException {
        String sql = "UPDATE agent_memory_embedding_job "
                + "SET status = 'FAILED', retry_count = ?, error_message = ?, updated_at = ? WHERE job_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, forceRetryCount);
            ps.setString(2, truncate(errorMessage, 1000));
            ps.setString(3, DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
            ps.setString(4, jobId);
            ps.executeUpdate();
        }
    }

    private void handleFailure(String jobId, String memoryId, int retryCount, Exception e) {
        try {
            int newRetryCount = retryCount + 1;
            if (newRetryCount > MAX_RETRIES) {
                String sql = "UPDATE agent_memory_embedding_job "
                        + "SET status = 'FAILED', retry_count = ?, error_message = ?, updated_at = ? WHERE job_id = ?";
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setInt(1, newRetryCount);
                    ps.setString(2, truncate(e.getMessage(), 1000));
                    ps.setString(3, DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
                    ps.setString(4, jobId);
                    ps.executeUpdate();
                }
                log.warn("Embedding job failed permanently after {} retries: jobId={}, memoryId={}, error={}",
                        newRetryCount, jobId, memoryId, e.getMessage());
            } else {
                String notBefore = DateTimeFormatter.ISO_INSTANT.format(
                        Instant.now().plus(Duration.ofSeconds(RETRY_BACKOFF_SECONDS)));
                String sql = "UPDATE agent_memory_embedding_job "
                        + "SET status = 'PENDING', retry_count = ?, error_message = ?, not_before = ?, updated_at = ? "
                        + "WHERE job_id = ?";
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setInt(1, newRetryCount);
                    ps.setString(2, truncate(e.getMessage(), 1000));
                    ps.setString(3, notBefore);
                    ps.setString(4, DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
                    ps.setString(5, jobId);
                    ps.executeUpdate();
                }
                log.warn("Embedding job will retry: jobId={}, memoryId={}, retry={}/{}, error={}",
                        jobId, memoryId, newRetryCount, MAX_RETRIES, e.getMessage());
            }
        } catch (SQLException sqlEx) {
            log.error("Failed to update embedding job status: jobId={}, error={}", jobId, sqlEx.getMessage());
        }
    }

    public void reindexAll() {
        if (!vectorIndex.available()) {
            log.warn("Vector index not available, skipping reindexAll");
            return;
        }

        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        try (Connection conn = dataSource.getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM agent_memory_vector_ref");
                log.info("Cleared agent_memory_vector_ref for reindex");
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM agent_memory_vec");
                log.info("Cleared agent_memory_vec for reindex");
            } catch (SQLException e) {
                log.warn("Failed to clear agent_memory_vec (table may not exist): {}", e.getMessage());
            }

            String selectMemSql = "SELECT memory_id FROM agent_memory WHERE status = 'ACTIVE'";
            String insertJobSql = "INSERT OR IGNORE INTO agent_memory_embedding_job "
                    + "(job_id, memory_id, action, status, not_before, created_at, updated_at) "
                    + "VALUES (?, ?, 'UPSERT', 'PENDING', ?, ?, ?)";

            int enqueued = 0;
            try (PreparedStatement psMem = conn.prepareStatement(selectMemSql);
                 PreparedStatement psJob = conn.prepareStatement(insertJobSql)) {
                try (ResultSet rs = psMem.executeQuery()) {
                    while (rs.next()) {
                        String memoryId = rs.getString("memory_id");
                        String jobId = UUID.randomUUID().toString();
                        psJob.setString(1, jobId);
                        psJob.setString(2, memoryId);
                        psJob.setString(3, now);
                        psJob.setString(4, now);
                        psJob.setString(5, now);
                        psJob.addBatch();
                        enqueued++;
                    }
                }
                psJob.executeBatch();
            }

            log.info("Reindex complete: enqueued {} active memories for embedding", enqueued);
        } catch (SQLException e) {
            log.error("Reindex failed: {}", e.getMessage());
            throw new RuntimeException("reindex failed", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
