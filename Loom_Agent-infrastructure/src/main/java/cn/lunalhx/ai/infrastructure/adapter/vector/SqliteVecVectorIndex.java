package cn.lunalhx.ai.infrastructure.adapter.vector;

import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryVectorIndex;
import cn.lunalhx.ai.domain.memory.model.valobj.EmbeddingVector;
import cn.lunalhx.ai.domain.memory.model.valobj.ScoredMemoryId;
import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class SqliteVecVectorIndex implements AgentMemoryVectorIndex {

    private static final Logger log = LoggerFactory.getLogger(SqliteVecVectorIndex.class);

    private static final String TABLE_NAME = "agent_memory_vec";

    private final DataSource dataSource;
    private final String extensionPath;
    private final int dimensions;
    private volatile boolean available;

    public SqliteVecVectorIndex(DataSource dataSource, String extensionPath, int dimensions) {
        this.dataSource = dataSource;
        this.extensionPath = extensionPath;
        this.dimensions = dimensions;
        init();
    }

    private void init() {
        try {
            boolean vecOk = verifyVecVersion();
            if (!vecOk) {
                available = false;
                return;
            }
            createIfNeeded();
            try (Connection conn = getVecConnection()) {
                available = tableExists(conn);
            }
            if (available) {
                log.info("sqlite-vec vector index initialized");
            } else {
                log.warn("sqlite-vec table creation failed, vector index disabled");
            }
        } catch (Exception e) {
            log.warn("failed to initialize sqlite-vec vector index: {}", e.getMessage());
            available = false;
        }
    }

    private Connection getVecConnection() throws SQLException {
        Connection conn = dataSource.getConnection();
        if (extensionPath != null && !extensionPath.isBlank()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT load_extension('" + extensionPath + "')");
            } catch (SQLException e) {
                // extension may already be loaded; non-fatal
            }
        }
        return conn;
    }

    private boolean verifyVecVersion() {
        try (Connection conn = getVecConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT vec_version()")) {
            if (rs.next()) {
                log.info("sqlite-vec version: {}", rs.getString(1));
                return true;
            }
        } catch (SQLException e) {
            log.warn("vec_version() check failed: {}", e.getMessage());
        }
        return false;
    }

    @Override
    public void createIfNeeded() {
        try (Connection conn = getVecConnection()) {
            if (tableExists(conn)) {
                return;
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE VIRTUAL TABLE " + TABLE_NAME + " USING vec0("
                        + "memory_rowid INTEGER PRIMARY KEY,"
                        + "workspace_key TEXT PARTITION KEY,"
                        + "status TEXT,"
                        + "type TEXT,"
                        + "importance INTEGER,"
                        + "embedding FLOAT[" + dimensions + "] distance_metric=cosine"
                        + ")");
                log.info("created vec0 virtual table: {}", TABLE_NAME);
            }
        } catch (SQLException e) {
            log.warn("failed to create vec0 table {}: {}", TABLE_NAME, e.getMessage());
        }
    }

    private boolean tableExists(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, TABLE_NAME);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public void upsert(long memoryRowId, EmbeddingVector vector, String workspaceKey,
                       String status, String type, int importance) {
        String embeddingJson = JSON.toJSONString(vector.values());
        try (Connection conn = getVecConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM " + TABLE_NAME + " WHERE memory_rowid = ?")) {
                    del.setLong(1, memoryRowId);
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO " + TABLE_NAME + "(memory_rowid, workspace_key, status, type, importance, embedding) "
                                + "VALUES (?, ?, ?, ?, ?, ?)")) {
                    ins.setLong(1, memoryRowId);
                    ins.setString(2, workspaceKey);
                    ins.setString(3, status);
                    ins.setString(4, type);
                    ins.setInt(5, importance);
                    ins.setString(6, embeddingJson);
                    ins.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.warn("vec0 upsert failed for memoryRowId {}: {}", memoryRowId, e.getMessage());
        }
    }

    @Override
    public void delete(long memoryRowId) {
        try (Connection conn = getVecConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM " + TABLE_NAME + " WHERE memory_rowid = ?")) {
            ps.setLong(1, memoryRowId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("vec0 delete failed for memoryRowId {}: {}", memoryRowId, e.getMessage());
        }
    }

    @Override
    public List<ScoredMemoryId> search(String workspaceKey, EmbeddingVector query, int k) {
        String embeddingJson = JSON.toJSONString(query.values());
        List<ScoredMemoryId> results = new ArrayList<>();
        try (Connection conn = getVecConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT v.memory_rowid, v.distance, r.memory_id "
                             + "FROM " + TABLE_NAME + " v "
                             + "LEFT JOIN agent_memory_vector_ref r ON v.memory_rowid = r.memory_rowid "
                             + "WHERE v.embedding MATCH ? "
                             + "AND k = ? "
                             + "AND v.workspace_key = ? "
                             + "AND v.status = 'ACTIVE' "
                             + "ORDER BY v.distance")) {
            ps.setString(1, embeddingJson);
            ps.setInt(2, k);
            ps.setString(3, workspaceKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long memoryRowId = rs.getLong("memory_rowid");
                    String memoryId = rs.getString("memory_id");
                    double distance = rs.getDouble("distance");
                    results.add(new ScoredMemoryId(memoryRowId, memoryId, distance));
                }
            }
        } catch (SQLException e) {
            log.warn("vec0 search failed for workspace {}: {}", workspaceKey, e.getMessage());
        }
        return results;
    }

    @Override
    public boolean available() {
        return available;
    }
}
