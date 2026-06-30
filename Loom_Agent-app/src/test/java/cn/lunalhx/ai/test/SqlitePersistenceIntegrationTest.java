package cn.lunalhx.ai.test;

import cn.lunalhx.ai.Application;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceUndoLockRepository;
import cn.lunalhx.ai.infrastructure.dao.AgentContextArtifactDao;
import cn.lunalhx.ai.infrastructure.dao.AgentPendingApprovalDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunCheckpointDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunDao;
import cn.lunalhx.ai.infrastructure.dao.AgentTraceEventDao;
import cn.lunalhx.ai.infrastructure.dao.AgentUndoSnapshotDao;
import cn.lunalhx.ai.infrastructure.dao.po.AgentContextArtifactPO;
import cn.lunalhx.ai.infrastructure.dao.po.AgentPendingApprovalPO;
import cn.lunalhx.ai.infrastructure.dao.po.AgentRunCheckpointPO;
import cn.lunalhx.ai.infrastructure.dao.po.AgentRunPO;
import cn.lunalhx.ai.infrastructure.dao.po.AgentTraceEventPO;
import cn.lunalhx.ai.infrastructure.dao.po.AgentUndoSnapshotPO;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

public class SqlitePersistenceIntegrationTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private ConfigurableApplicationContext context;

    @Before
    public void setUp() throws Exception {
        context = startContext(temporaryFolder.newFolder().getAbsolutePath());
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    public void shouldPersistAndQueryAllAgentState() {
        AgentRunDao runDao = context.getBean(AgentRunDao.class);
        AgentRunPO run = new AgentRunPO();
        run.setRunId("run-1");
        run.setRunKind("ROOT");
        run.setDepth(0);
        run.setQuestion("test");
        run.setStatus("RUNNING");
        run.setStep(1);
        run.setUsedTokens(10L);
        run.setEstimatedCost(new BigDecimal("0.125"));
        assertEquals(1, runDao.upsert(run));
        AgentRunPO restoredRun = runDao.selectByRunId("run-1");
        assertNotNull(restoredRun.getCreateTime());
        assertEquals(new BigDecimal("0.125"), restoredRun.getEstimatedCost());

        AgentRunCheckpointDao checkpointDao = context.getBean(AgentRunCheckpointDao.class);
        AgentRunCheckpointPO checkpoint = checkpoint("run-1");
        assertEquals(Long.valueOf(1), checkpointDao.insertNext(checkpoint));
        assertEquals(Long.valueOf(2), checkpointDao.insertNext(checkpoint));
        assertEquals(Long.valueOf(2), checkpointDao.selectLatest("run-1").getVersion());

        AgentTraceEventDao traceDao = context.getBean(AgentTraceEventDao.class);
        AgentTraceEventPO event = trace("run-1");
        assertEquals(Long.valueOf(1), traceDao.insertNext(event));
        assertEquals(Long.valueOf(2), traceDao.insertNext(event));
        assertEquals(2, traceDao.selectByRunId("run-1").size());

        AgentPendingApprovalDao approvalDao = context.getBean(AgentPendingApprovalDao.class);
        AgentPendingApprovalPO approval = new AgentPendingApprovalPO();
        approval.setApprovalId("approval-1");
        approval.setRunId("run-1");
        approval.setTool("write_file");
        approval.setPermissionLevel("WRITE_CONFIRM");
        approval.setCreatedAt(Instant.now());
        approval.setExpiresAt(Instant.now().plusSeconds(60));
        approval.setConsumed(0);
        assertEquals(1, approvalDao.upsert(approval));
        assertEquals(1, approvalDao.markConsumed("approval-1"));
        assertEquals(Integer.valueOf(1), approvalDao.selectByApprovalId("approval-1").getConsumed());

        AgentContextArtifactDao artifactDao = context.getBean(AgentContextArtifactDao.class);
        AgentContextArtifactPO artifact = new AgentContextArtifactPO();
        artifact.setArtifactId("artifact-1");
        artifact.setRunId("run-1");
        artifact.setRootRunId("run-1");
        artifact.setKind("TOOL_RESULT");
        artifact.setStorageUri("file:///tmp/artifact-1");
        artifact.setPreview("sqlite searchable preview");
        artifact.setSha256("abc");
        artifact.setOriginalChars(10);
        artifact.setRetainedChars(5);
        artifact.setCreateTime(Instant.now());
        assertEquals(1, artifactDao.insert(artifact));
        assertEquals(1, artifactDao.searchByRootRunId("run-1", "searchable", 10).size());

        AgentUndoSnapshotDao undoDao = context.getBean(AgentUndoSnapshotDao.class);
        AgentUndoSnapshotPO snapshot = new AgentUndoSnapshotPO();
        snapshot.setSnapshotId("snapshot-1");
        snapshot.setRunId("run-1");
        snapshot.setWorkspace("/tmp/workspace");
        snapshot.setStatus("READY");
        snapshot.setVersion(0L);
        snapshot.setCreatedAt(Instant.now());
        snapshot.setExpiresAt(Instant.now().plusSeconds(60));
        assertEquals(1, undoDao.upsert(snapshot));
        assertEquals(1, undoDao.updateStatus("snapshot-1", "READY", "UNDONE", 0L));
        assertEquals("UNDONE", undoDao.selectBySnapshotId("snapshot-1").getStatus());
    }

    @Test
    public void shouldAllocateCheckpointVersionsAtomically() throws Exception {
        AgentRunCheckpointDao dao = context.getBean(AgentRunCheckpointDao.class);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                tasks.add(() -> dao.insertNext(checkpoint("concurrent-run")));
            }
            List<Future<Long>> futures = executor.invokeAll(tasks);
            List<Long> versions = new ArrayList<>();
            for (Future<Long> future : futures) {
                versions.add(future.get());
            }
            assertEquals(12, new HashSet<>(versions).size());
            assertEquals(Long.valueOf(12), dao.selectLatest("concurrent-run").getVersion());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldAcquireAndReleaseWorkspaceLockAtomically() {
        WorkspaceUndoLockRepository locks = context.getBean(WorkspaceUndoLockRepository.class);
        String workspace = "/tmp/locked-workspace";
        assertTrue(locks.acquire(workspace, "run-1", Instant.now().plusSeconds(60)));
        assertFalse(locks.acquire(workspace, "run-2", Instant.now().plusSeconds(60)));
        assertFalse(locks.release(workspace, "run-2"));
        assertTrue(locks.release(workspace, "run-1"));
        assertTrue(locks.acquire(workspace, "run-2", Instant.now().plusSeconds(60)));
    }

    private ConfigurableApplicationContext startContext(String dataDir) {
        return new SpringApplicationBuilder(Application.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .run(
                        "--loom.agent.persistence.mode=sqlite",
                        "--loom.agent.persistence.data-dir=" + dataDir,
                        "--loom.mcp.enabled=false",
                        "--logging.level.root=WARN",
                        "--spring.main.banner-mode=off"
                );
    }

    private static AgentRunCheckpointPO checkpoint(String runId) {
        AgentRunCheckpointPO checkpoint = new AgentRunCheckpointPO();
        checkpoint.setRunId(runId);
        checkpoint.setCurrentNode("model_call");
        checkpoint.setContextJson("{}");
        checkpoint.setPlanJson("{}");
        checkpoint.setReason("test");
        return checkpoint;
    }

    private static AgentTraceEventPO trace(String runId) {
        AgentTraceEventPO event = new AgentTraceEventPO();
        event.setTraceId(runId);
        event.setRootRunId(runId);
        event.setRunId(runId);
        event.setEventType("node_start");
        event.setTokenUsageJson("{}");
        event.setCostJson("{}");
        event.setMetadataJson("{}");
        event.setReplayable(true);
        event.setSensitiveRedacted(false);
        return event;
    }
}
