package cn.lunalhx.ai.test;

import cn.lunalhx.ai.config.PersistenceAutoConfig;
import cn.lunalhx.ai.config.PersistenceProperties;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.adapter.port.UndoSnapshotRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceUndoLockRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryStoreProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryApprovalStore;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryBackgroundShellTaskRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryContextArtifactRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryUndoSnapshotRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryWorkspaceUndoLockRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisApprovalStore;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisBackgroundShellTaskRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisContextArtifactRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisTraceRecorder;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisUndoSnapshotRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisWorkspaceUndoLockRepository;
import cn.lunalhx.ai.infrastructure.context.InMemoryContextBlobStore;
import cn.lunalhx.ai.infrastructure.context.LocalFileContextBlobStore;
import cn.lunalhx.ai.infrastructure.dao.AgentContextArtifactDao;
import cn.lunalhx.ai.infrastructure.dao.AgentPendingApprovalDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunCheckpointDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunDao;
import cn.lunalhx.ai.infrastructure.dao.AgentTraceEventDao;
import cn.lunalhx.ai.infrastructure.dao.AgentUndoSnapshotDao;
import cn.lunalhx.ai.infrastructure.dao.AgentWorkspaceUndoLockDao;
import cn.lunalhx.ai.infrastructure.dao.BackgroundShellTaskDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.Assert.*;

public class PersistenceConfigTest {

    private final PersistenceAutoConfig config = new PersistenceAutoConfig();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemoryStoreProperties memProps = new MemoryStoreProperties();

    // ---- SQLITE mode: all 9 beans return correct impl types ----

    @Test
    public void sqliteModeWithDaosReturnsMybatisBeans() {
        PersistenceProperties persistence = persistence(PersistenceProperties.Mode.SQLITE);

        AgentRunRepository runRepo = config.agentRunRepository(persistence, provider(new MockAgentRunDao()), memProps);
        AgentCheckpointRepository checkpointRepo = config.agentCheckpointRepository(persistence, provider(new MockAgentRunCheckpointDao()), objectMapper, memProps);
        ApprovalStore approvalStore = config.approvalStore(persistence, provider(new MockAgentPendingApprovalDao()), objectMapper, memProps);
        TraceRecorder traceRecorder = config.traceRecorder(persistence, provider(new MockAgentTraceEventDao()), objectMapper, memProps);
        ContextArtifactRepository artifactRepo = config.contextArtifactRepository(persistence, provider(new MockAgentContextArtifactDao()), memProps);
        UndoSnapshotRepository undoRepo = config.undoSnapshotRepository(persistence, provider(new MockAgentUndoSnapshotDao()), objectMapper, memProps);
        WorkspaceUndoLockRepository undoLockRepo = config.workspaceUndoLockRepository(persistence, provider(new MockAgentWorkspaceUndoLockDao()), memProps);
        BackgroundShellTaskRepository bgTaskRepo = config.backgroundShellTaskRepository(persistence, provider(new MockBackgroundShellTaskDao()));

        assertTrue("AgentRunRepository should be MyBatis", runRepo instanceof MybatisAgentRunRepository);
        assertTrue("AgentCheckpointRepository should be MyBatis", checkpointRepo instanceof MybatisAgentCheckpointRepository);
        assertTrue("ApprovalStore should be MyBatis", approvalStore instanceof MybatisApprovalStore);
        assertTrue("TraceRecorder should be MyBatis", traceRecorder instanceof MybatisTraceRecorder);
        assertTrue("ContextArtifactRepository should be MyBatis", artifactRepo instanceof MybatisContextArtifactRepository);
        assertTrue("UndoSnapshotRepository should be MyBatis", undoRepo instanceof MybatisUndoSnapshotRepository);
        assertTrue("WorkspaceUndoLockRepository should be MyBatis", undoLockRepo instanceof MybatisWorkspaceUndoLockRepository);
        assertTrue("BackgroundShellTaskRepository should be MyBatis", bgTaskRepo instanceof MybatisBackgroundShellTaskRepository);
    }

    // ---- MEMORY mode: all 9 beans return InMemory impl types ----

    @Test
    public void memoryModeForcesInMemoryEvenWithDaos() {
        PersistenceProperties persistence = persistence(PersistenceProperties.Mode.MEMORY);

        AgentRunRepository runRepo = config.agentRunRepository(persistence, provider(new MockAgentRunDao()), memProps);
        AgentCheckpointRepository checkpointRepo = config.agentCheckpointRepository(persistence, provider(new MockAgentRunCheckpointDao()), objectMapper, memProps);
        ApprovalStore approvalStore = config.approvalStore(persistence, provider(new MockAgentPendingApprovalDao()), objectMapper, memProps);
        TraceRecorder traceRecorder = config.traceRecorder(persistence, provider(new MockAgentTraceEventDao()), objectMapper, memProps);
        ContextArtifactRepository artifactRepo = config.contextArtifactRepository(persistence, provider(new MockAgentContextArtifactDao()), memProps);
        UndoSnapshotRepository undoRepo = config.undoSnapshotRepository(persistence, provider(new MockAgentUndoSnapshotDao()), objectMapper, memProps);
        WorkspaceUndoLockRepository undoLockRepo = config.workspaceUndoLockRepository(persistence, provider(new MockAgentWorkspaceUndoLockDao()), memProps);
        BackgroundShellTaskRepository bgTaskRepo = config.backgroundShellTaskRepository(persistence, provider(new MockBackgroundShellTaskDao()));

        assertTrue(runRepo instanceof InMemoryAgentRunRepository);
        assertTrue(checkpointRepo instanceof InMemoryAgentCheckpointRepository);
        assertTrue(approvalStore instanceof InMemoryApprovalStore);
        assertTrue(traceRecorder instanceof InMemoryTraceRecorder);
        assertTrue(artifactRepo instanceof InMemoryContextArtifactRepository);
        assertTrue(undoRepo instanceof InMemoryUndoSnapshotRepository);
        assertTrue(undoLockRepo instanceof InMemoryWorkspaceUndoLockRepository);
        assertTrue(bgTaskRepo instanceof InMemoryBackgroundShellTaskRepository);
    }

    // ---- MEMORY mode: does not resolve DAOs ----

    @Test
    public void memoryModeIgnoresMissingDaos() {
        PersistenceProperties persistence = persistence(PersistenceProperties.Mode.MEMORY);

        AgentRunRepository runRepo = config.agentRunRepository(persistence, emptyProvider(), memProps);
        AgentCheckpointRepository checkpointRepo = config.agentCheckpointRepository(persistence, emptyProvider(), objectMapper, memProps);
        ApprovalStore approvalStore = config.approvalStore(persistence, emptyProvider(), objectMapper, memProps);
        TraceRecorder traceRecorder = config.traceRecorder(persistence, emptyProvider(), objectMapper, memProps);
        ContextArtifactRepository artifactRepo = config.contextArtifactRepository(persistence, emptyProvider(), memProps);
        UndoSnapshotRepository undoRepo = config.undoSnapshotRepository(persistence, emptyProvider(), objectMapper, memProps);
        WorkspaceUndoLockRepository undoLockRepo = config.workspaceUndoLockRepository(persistence, emptyProvider(), memProps);
        BackgroundShellTaskRepository bgTaskRepo = config.backgroundShellTaskRepository(persistence, emptyProvider());

        assertNotNull(runRepo);
        assertNotNull(checkpointRepo);
        assertNotNull(approvalStore);
        assertNotNull(traceRecorder);
        assertNotNull(artifactRepo);
        assertNotNull(undoRepo);
        assertNotNull(undoLockRepo);
        assertNotNull(bgTaskRepo);
    }

    // ---- SQLITE mode: missing DAO throws with correct DAO type name ----

    @Test
    public void sqliteModeMissingDaoThrows() {
        PersistenceProperties persistence = persistence(PersistenceProperties.Mode.SQLITE);

        try {
            config.agentRunRepository(persistence, emptyProvider(), memProps);
            fail("Should have thrown");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("AgentRunDao"));
        }
    }

    @Test
    public void sqliteModeMissingCheckpointDaoThrows() {
        PersistenceProperties persistence = persistence(PersistenceProperties.Mode.SQLITE);

        try {
            config.agentCheckpointRepository(persistence, emptyProvider(), objectMapper, memProps);
            fail("Should have thrown");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("AgentRunCheckpointDao"));
        }
    }

    @Test
    public void sqliteModeMissingApprovalDaoThrows() {
        PersistenceProperties persistence = persistence(PersistenceProperties.Mode.SQLITE);

        try {
            config.approvalStore(persistence, emptyProvider(), objectMapper, memProps);
            fail("Should have thrown");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("AgentPendingApprovalDao"));
        }
    }

    @Test
    public void sqliteModeMissingTraceEventDaoThrows() {
        PersistenceProperties persistence = persistence(PersistenceProperties.Mode.SQLITE);

        try {
            config.traceRecorder(persistence, emptyProvider(), objectMapper, memProps);
            fail("Should have thrown");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("AgentTraceEventDao"));
        }
    }

    @Test
    public void sqliteModeMissingContextArtifactDaoThrows() {
        PersistenceProperties persistence = persistence(PersistenceProperties.Mode.SQLITE);

        try {
            config.contextArtifactRepository(persistence, emptyProvider(), memProps);
            fail("Should have thrown");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("AgentContextArtifactDao"));
        }
    }

    @Test
    public void sqliteModeMissingUndoSnapshotDaoThrows() {
        PersistenceProperties persistence = persistence(PersistenceProperties.Mode.SQLITE);

        try {
            config.undoSnapshotRepository(persistence, emptyProvider(), objectMapper, memProps);
            fail("Should have thrown");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("AgentUndoSnapshotDao"));
        }
    }

    @Test
    public void sqliteModeMissingWorkspaceUndoLockDaoThrows() {
        PersistenceProperties persistence = persistence(PersistenceProperties.Mode.SQLITE);

        try {
            config.workspaceUndoLockRepository(persistence, emptyProvider(), memProps);
            fail("Should have thrown");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("AgentWorkspaceUndoLockDao"));
        }
    }

    @Test
    public void sqliteModeMissingBackgroundShellTaskDaoThrows() {
        PersistenceProperties persistence = persistence(PersistenceProperties.Mode.SQLITE);

        try {
            config.backgroundShellTaskRepository(persistence, emptyProvider());
            fail("Should have thrown");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("BackgroundShellTaskDao"));
        }
    }

    // ---- ContextBlobStore: mode-specific impl types ----

    @Test
    public void sqliteModeContextBlobStoreUsesLocalFile() {
        PersistenceProperties persistence = persistence(PersistenceProperties.Mode.SQLITE);
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.getContext().setStorageRoot("/tmp/test-artifacts");

        ContextBlobStore blobStore = config.contextBlobStore(persistence, props, memProps);
        assertTrue(blobStore instanceof LocalFileContextBlobStore);
    }

    @Test
    public void memoryModeContextBlobStoreUsesInMemory() {
        PersistenceProperties persistence = persistence(PersistenceProperties.Mode.MEMORY);
        AgentRuntimeProperties props = new AgentRuntimeProperties();

        ContextBlobStore blobStore = config.contextBlobStore(persistence, props, memProps);
        assertTrue(blobStore instanceof InMemoryContextBlobStore);
    }

    // ---- Constructors with ObjectMapper work correctly ----

    @Test
    public void sqliteModeBeansWithObjectMapperAreCreated() {
        PersistenceProperties persistence = persistence(PersistenceProperties.Mode.SQLITE);

        AgentCheckpointRepository checkpointRepo = config.agentCheckpointRepository(
                persistence, provider(new MockAgentRunCheckpointDao()), objectMapper, memProps);
        ApprovalStore approvalStore = config.approvalStore(
                persistence, provider(new MockAgentPendingApprovalDao()), objectMapper, memProps);
        TraceRecorder traceRecorder = config.traceRecorder(
                persistence, provider(new MockAgentTraceEventDao()), objectMapper, memProps);
        UndoSnapshotRepository undoRepo = config.undoSnapshotRepository(
                persistence, provider(new MockAgentUndoSnapshotDao()), objectMapper, memProps);

        assertNotNull(checkpointRepo);
        assertNotNull(approvalStore);
        assertNotNull(traceRecorder);
        assertNotNull(undoRepo);
    }

    // ---- helpers ----

    private static PersistenceProperties persistence(PersistenceProperties.Mode mode) {
        PersistenceProperties p = new PersistenceProperties();
        p.setMode(mode);
        return p;
    }

    private static <T> ObjectProvider<T> provider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }

            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }
        };
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public T getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public T getIfUnique() {
                return null;
            }
        };
    }

    // ---- mock DAOs (minimal stubs) ----

    private static class MockAgentRunDao implements AgentRunDao {
        @Override public int upsert(cn.lunalhx.ai.infrastructure.dao.po.AgentRunPO run) { return 1; }
        @Override public cn.lunalhx.ai.infrastructure.dao.po.AgentRunPO selectByRunId(String runId) { return null; }
        @Override public java.util.List<cn.lunalhx.ai.infrastructure.dao.po.AgentRunPO> selectByParentRunId(String parentRunId) { return java.util.List.of(); }
        @Override public cn.lunalhx.ai.infrastructure.dao.po.AgentRunPO selectLatestRootByConversationId(String conversationId) { return null; }
        @Override public java.util.List<cn.lunalhx.ai.infrastructure.dao.po.AgentRunPO> selectByConversationId(String conversationId) { return java.util.List.of(); }
        @Override public int deleteByConversationId(String conversationId) { return 0; }
        @Override public java.util.List<cn.lunalhx.ai.infrastructure.dao.po.ConversationSummaryPO> selectConversationSummaries() { return java.util.List.of(); }
    }
    private static class MockAgentRunCheckpointDao implements AgentRunCheckpointDao {
        @Override public Long insertNext(cn.lunalhx.ai.infrastructure.dao.po.AgentRunCheckpointPO checkpoint) { return 1L; }
        @Override public cn.lunalhx.ai.infrastructure.dao.po.AgentRunCheckpointPO selectLatest(String runId) { return null; }
        @Override public int deleteByRunIds(java.util.List<String> runIds) { return 0; }
    }
    private static class MockAgentPendingApprovalDao implements AgentPendingApprovalDao {
        @Override public int upsert(cn.lunalhx.ai.infrastructure.dao.po.AgentPendingApprovalPO approval) { return 1; }
        @Override public cn.lunalhx.ai.infrastructure.dao.po.AgentPendingApprovalPO selectByApprovalId(String approvalId) { return null; }
        @Override public int markConsumed(String approvalId) { return 1; }
        @Override public int deleteByConversationId(String conversationId) { return 0; }
    }
    private static class MockAgentTraceEventDao implements AgentTraceEventDao {
        @Override public Long insertNext(cn.lunalhx.ai.infrastructure.dao.po.AgentTraceEventPO event) { return 1L; }
        @Override public java.util.List<cn.lunalhx.ai.infrastructure.dao.po.AgentTraceEventPO> selectByRunId(String runId) { return java.util.List.of(); }
        @Override public java.util.List<cn.lunalhx.ai.infrastructure.dao.po.AgentTraceEventPO> selectByTraceId(String traceId) { return java.util.List.of(); }
        @Override public int deleteByRunIds(java.util.List<String> runIds) { return 0; }
        @Override public int deleteByRootRunIds(java.util.List<String> rootRunIds) { return 0; }
    }
    private static class MockAgentContextArtifactDao implements AgentContextArtifactDao {
        @Override public int insert(cn.lunalhx.ai.infrastructure.dao.po.AgentContextArtifactPO artifact) { return 1; }
        @Override public cn.lunalhx.ai.infrastructure.dao.po.AgentContextArtifactPO selectByArtifactIdAndRootRunId(String artifactId, String rootRunId) { return null; }
        @Override public java.util.List<cn.lunalhx.ai.infrastructure.dao.po.AgentContextArtifactPO> selectByRootRunId(String rootRunId) { return java.util.List.of(); }
        @Override public java.util.List<cn.lunalhx.ai.infrastructure.dao.po.AgentContextArtifactPO> searchByRootRunId(String rootRunId, String query, int limit) { return java.util.List.of(); }
        @Override public java.util.List<cn.lunalhx.ai.infrastructure.dao.po.AgentContextArtifactPO> selectByConversationId(String conversationId) { return java.util.List.of(); }
        @Override public int deleteByConversationId(String conversationId) { return 0; }
    }
    private static class MockAgentUndoSnapshotDao implements AgentUndoSnapshotDao {
        @Override public int insert(cn.lunalhx.ai.infrastructure.dao.po.AgentUndoSnapshotPO po) { return 1; }
        @Override public int upsert(cn.lunalhx.ai.infrastructure.dao.po.AgentUndoSnapshotPO po) { return 1; }
        @Override public cn.lunalhx.ai.infrastructure.dao.po.AgentUndoSnapshotPO selectBySnapshotId(String snapshotId) { return null; }
        @Override public cn.lunalhx.ai.infrastructure.dao.po.AgentUndoSnapshotPO selectByRunId(String runId) { return null; }
        @Override public cn.lunalhx.ai.infrastructure.dao.po.AgentUndoSnapshotPO selectLatestByWorkspace(String workspace) { return null; }
        @Override public cn.lunalhx.ai.infrastructure.dao.po.AgentUndoSnapshotPO selectLatestByConversationId(String conversationId) { return null; }
        @Override public int updateStatus(String snapshotId, String expected, String next, Long expectedVersion) { return 1; }
        @Override public java.util.List<cn.lunalhx.ai.infrastructure.dao.po.AgentUndoSnapshotPO> selectExpired(java.time.Instant now) { return java.util.List.of(); }
        @Override public int expireByStatus(String snapshotId, String expected, String next, Long expectedVersion) { return 1; }
        @Override public java.util.List<cn.lunalhx.ai.infrastructure.dao.po.AgentUndoSnapshotPO> selectByConversationId(String conversationId) { return java.util.List.of(); }
        @Override public int deleteByConversationId(String conversationId) { return 0; }
    }
    private static class MockAgentWorkspaceUndoLockDao implements AgentWorkspaceUndoLockDao {
        @Override public int tryAcquire(cn.lunalhx.ai.infrastructure.dao.po.AgentWorkspaceUndoLockPO po) { return 1; }
        @Override public int deleteOwned(String workspace, String runId) { return 1; }
        @Override public cn.lunalhx.ai.infrastructure.dao.po.AgentWorkspaceUndoLockPO selectByWorkspace(String workspace) { return null; }
        @Override public int deleteStaleBefore(java.time.Instant threshold) { return 0; }
    }
    private static class MockBackgroundShellTaskDao implements BackgroundShellTaskDao {
        @Override public int upsert(cn.lunalhx.ai.infrastructure.dao.po.BackgroundShellTaskPO po) { return 1; }
        @Override public cn.lunalhx.ai.infrastructure.dao.po.BackgroundShellTaskPO selectByTaskId(String taskId) { return null; }
        @Override public java.util.List<cn.lunalhx.ai.infrastructure.dao.po.BackgroundShellTaskPO> selectByRunId(String runId) { return java.util.List.of(); }
        @Override public java.util.List<cn.lunalhx.ai.infrastructure.dao.po.BackgroundShellTaskPO> selectRunningByRunId(String runId) { return java.util.List.of(); }
        @Override public java.util.List<cn.lunalhx.ai.infrastructure.dao.po.BackgroundShellTaskPO> selectStaleRunning(java.util.List<String> statuses) { return java.util.List.of(); }
        @Override public int markNotified(String taskId) { return 1; }
        @Override public void deleteByRunId(String runId) { }
        @Override public void deleteByConversationId(String conversationId) { }
    }
}
