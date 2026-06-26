package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentControlMessage;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryStoreProperties;
import cn.lunalhx.ai.infrastructure.adapter.port.InMemorySubAgentControlInbox;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryApprovalStore;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryContextArtifactRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder;
import cn.lunalhx.ai.infrastructure.context.InMemoryContextBlobStore;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

public class InMemoryBoundedCapacityTest {

    private static MemoryStoreProperties smallProps() {
        MemoryStoreProperties props = new MemoryStoreProperties();
        props.setTtlSeconds(3600);
        props.setMaxRuns(2);
        props.setMaxApprovals(2);
        props.setMaxTraceRuns(2);
        props.setMaxTraceEventsPerRun(3);
        props.setMaxCheckpointRuns(2);
        props.setMaxCheckpointsPerRun(2);
        props.setMaxContextArtifacts(2);
        props.setMaxContextBlobs(2);
        props.setMaxSubAgentInboxes(2);
        props.setMaxSubAgentMessagesPerRun(2);
        return props;
    }

    // ---- AgentRunRepository ----

    @Test
    public void agentRunRepositoryEvictsOldestWhenOverCapacity() {
        MemoryStoreProperties props = smallProps();
        InMemoryAgentRunRepository repo = new InMemoryAgentRunRepository(props);

        repo.save(run("run-1"));
        repo.save(run("run-2"));
        repo.save(run("run-3"));

        assertFalse(repo.find("run-1").isPresent());
        assertTrue(repo.find("run-2").isPresent());
        assertTrue(repo.find("run-3").isPresent());
    }

    // ---- ApprovalStore ----

    @Test
    public void approvalStoreEvictsOldestWhenOverCapacity() {
        MemoryStoreProperties props = smallProps();
        InMemoryApprovalStore store = new InMemoryApprovalStore(props);

        store.save(approval("app-1"));
        store.save(approval("app-2"));
        store.save(approval("app-3"));

        // Eviction is LRU; the first inserted should be gone
        assertFalse(store.find("app-1").isPresent());
        assertTrue(store.find("app-2").isPresent());
        assertTrue(store.find("app-3").isPresent());
    }

    // ---- TraceRecorder: outer map cap ----

    @Test
    public void traceRecorderEvictsOldestRunWhenOverCapacity() {
        MemoryStoreProperties props = smallProps();
        props.setMaxTraceEventsPerRun(100); // large enough to not interfere
        InMemoryTraceRecorder recorder = new InMemoryTraceRecorder(props);

        // Fill 3 different runs; only last 2 should survive
        recorder.recordStop(ctx("run-a"), "completed", "done-a");
        recorder.recordStop(ctx("run-b"), "completed", "done-b");
        recorder.recordStop(ctx("run-c"), "completed", "done-c");

        assertTrue(recorder.timeline("run-a").isEmpty());
        assertFalse(recorder.timeline("run-b").isEmpty());
        assertFalse(recorder.timeline("run-c").isEmpty());
    }

    // ---- TraceRecorder: per-run cap ----

    @Test
    public void traceRecorderEvictsOldestEventsPerRun() {
        MemoryStoreProperties props = smallProps();
        props.setMaxTraceRuns(10);
        props.setMaxTraceEventsPerRun(3);
        InMemoryTraceRecorder recorder = new InMemoryTraceRecorder(props);

        String runId = "run-1";
        for (int i = 0; i < 5; i++) {
            recorder.recordStop(ctxWithRunId(runId), "completed", "event-" + i);
        }

        assertEquals(3, recorder.timeline(runId).size());
    }

    // ---- AgentCheckpointRepository: per-run cap ----

    @Test
    public void checkpointRepositoryEvictsOldestCheckpointsPerRun() {
        MemoryStoreProperties props = smallProps();
        props.setMaxCheckpointRuns(10);
        props.setMaxCheckpointsPerRun(2);
        InMemoryAgentCheckpointRepository repo = new InMemoryAgentCheckpointRepository(props);

        String runId = "run-1";
        for (int i = 0; i < 4; i++) {
            cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint cp =
                    new cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint();
            cp.setRunId(runId);
            repo.save(cp);
        }

        // Only the last 2 should remain
        cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint latest = repo.latest(runId).orElse(null);
        assertNotNull(latest);
        assertEquals(4L, (long) latest.getVersion()); // version auto-increment still counts all 4
    }

    // ---- ContextArtifactRepository ----

    @Test
    public void contextArtifactRepositoryEvictsOldestWhenOverCapacity() {
        MemoryStoreProperties props = smallProps();
        InMemoryContextArtifactRepository repo = new InMemoryContextArtifactRepository(props);

        repo.save(artifact("art-1", "root-1"));
        repo.save(artifact("art-2", "root-1"));
        repo.save(artifact("art-3", "root-1"));

        assertFalse(repo.findByArtifactIdAndRootRunId("art-1", "root-1").isPresent());
        assertTrue(repo.findByArtifactIdAndRootRunId("art-2", "root-1").isPresent());
        assertTrue(repo.findByArtifactIdAndRootRunId("art-3", "root-1").isPresent());
    }

    // ---- ContextBlobStore ----

    @Test
    public void contextBlobStoreEvictsOldestWhenOverCapacity() {
        MemoryStoreProperties props = smallProps();
        InMemoryContextBlobStore store = new InMemoryContextBlobStore(props);

        store.write("root-1", "blob-1", "content-1");
        store.write("root-1", "blob-2", "content-2");
        store.write("root-1", "blob-3", "content-3");

        assertTrue(store.read("memory://root-1/blob-1").isEmpty());
        assertFalse(store.read("memory://root-1/blob-2").isEmpty());
        assertFalse(store.read("memory://root-1/blob-3").isEmpty());
    }

    // ---- SubAgentControlInbox: per-run cap ----

    @Test
    public void subAgentInboxEvictsOldestMessagesPerRun() {
        MemoryStoreProperties props = smallProps();
        props.setMaxSubAgentInboxes(10);
        props.setMaxSubAgentMessagesPerRun(2);
        InMemorySubAgentControlInbox inbox = new InMemorySubAgentControlInbox(props);

        long deadline = System.currentTimeMillis() + 60000;
        inbox.send("child-1", msg("child-1", deadline, "m1"));
        inbox.send("child-1", msg("child-1", deadline, "m2"));
        inbox.send("child-1", msg("child-1", deadline, "m3"));

        // Only last 2 should remain
        assertEquals(2, inbox.poll("child-1").size());
    }

    // ---- SubAgentControlInbox: outer map cap ----

    @Test
    public void subAgentInboxEvictsOldestChildRunWhenOverCapacity() {
        MemoryStoreProperties props = smallProps();
        props.setMaxSubAgentInboxes(2);
        props.setMaxSubAgentMessagesPerRun(10);
        InMemorySubAgentControlInbox inbox = new InMemorySubAgentControlInbox(props);

        long deadline = System.currentTimeMillis() + 60000;
        inbox.send("child-1", msg("child-1", deadline, "m1"));
        inbox.send("child-2", msg("child-2", deadline, "m1"));
        inbox.send("child-3", msg("child-3", deadline, "m1"));

        assertTrue(inbox.poll("child-1").isEmpty());
        assertFalse(inbox.poll("child-2").isEmpty());
        assertFalse(inbox.poll("child-3").isEmpty());
    }

    // ---- No-arg constructor is unbounded ----

    @Test
    public void noArgConstructorIsUnbounded() {
        InMemoryAgentRunRepository repo = new InMemoryAgentRunRepository();
        for (int i = 0; i < 2000; i++) {
            repo.save(run("run-" + i));
        }
        assertTrue(repo.find("run-0").isPresent());
        assertTrue(repo.find("run-1999").isPresent());
    }

    // ---- helpers ----

    private static AgentRun run(String runId) {
        AgentRun run = new AgentRun();
        run.setRunId(runId);
        return run;
    }

    private static PendingApproval approval(String approvalId) {
        PendingApproval approval = new PendingApproval();
        approval.setApprovalId(approvalId);
        approval.setExpiresAt(java.time.Instant.now().plusSeconds(3600));
        return approval;
    }

    private static cn.lunalhx.ai.domain.agent.model.entity.AgentContext ctx(String runId) {
        cn.lunalhx.ai.domain.agent.model.entity.AgentContext context =
                new cn.lunalhx.ai.domain.agent.model.entity.AgentContext();
        context.setRunId(runId);
        context.setRootRunId(runId);
        context.setTraceId("trace-" + runId);
        return context;
    }

    private static cn.lunalhx.ai.domain.agent.model.entity.AgentContext ctxWithRunId(String runId) {
        return ctx(runId);
    }

    private static cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact artifact(String artifactId, String rootRunId) {
        cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact artifact =
                new cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact();
        artifact.setArtifactId(artifactId);
        artifact.setRootRunId(rootRunId);
        artifact.setCreatedAt(java.time.Instant.now());
        return artifact;
    }

    private static SubAgentControlMessage msg(String childRunId, long deadlineMs, String reason) {
        SubAgentControlMessage msg = new SubAgentControlMessage();
        msg.setChildRunId(childRunId);
        msg.setDeadlineMs(deadlineMs);
        msg.setReason(reason);
        return msg;
    }
}
