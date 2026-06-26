package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryStoreProperties;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryApprovalStore;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryContextArtifactRepository;
import cn.lunalhx.ai.infrastructure.context.InMemoryContextBlobStore;
import org.junit.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.Assert.*;

public class InMemoryBoundedTtlTest {

    private static MemoryStoreProperties shortTtlProps() {
        MemoryStoreProperties props = new MemoryStoreProperties();
        props.setTtlSeconds(1);
        props.setMaxRuns(100);
        props.setMaxApprovals(100);
        props.setMaxContextArtifacts(100);
        props.setMaxContextBlobs(100);
        return props;
    }

    @Test
    public void agentRunRepositoryTtlEvictsAfterAccessExpiry() throws InterruptedException {
        MemoryStoreProperties props = shortTtlProps();
        InMemoryAgentRunRepository repo = new InMemoryAgentRunRepository(props);

        repo.save(run("run-1"));
        Thread.sleep(1200);
        // Access run-2 triggers cache maintenance which may evict run-1
        repo.save(run("run-2"));

        assertFalse("run-1 should be evicted after TTL", repo.find("run-1").isPresent());
        assertTrue("run-2 should still be present", repo.find("run-2").isPresent());
    }

    @Test
    public void approvalStoreTtlEvictsAfterAccessExpiry() throws InterruptedException {
        MemoryStoreProperties props = shortTtlProps();
        InMemoryApprovalStore store = new InMemoryApprovalStore(props);

        PendingApproval approval = approval("app-1");
        store.save(approval);
        Thread.sleep(1200);
        store.save(approval("app-2")); // trigger maintenance

        assertFalse("app-1 should be evicted after TTL", store.find("app-1").isPresent());
        assertTrue("app-2 should still be present", store.find("app-2").isPresent());
    }

    @Test
    public void approvalStoreStillEnforcesBusinessExpiry() {
        MemoryStoreProperties props = new MemoryStoreProperties();
        props.setTtlSeconds(3600);
        props.setMaxApprovals(100);
        InMemoryApprovalStore store = new InMemoryApprovalStore(props);

        PendingApproval approval = new PendingApproval();
        approval.setApprovalId("expired-1");
        approval.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        store.save(approval);

        assertFalse("expired approval should not be returned", store.find("expired-1").isPresent());
    }

    @Test
    public void contextArtifactRepositoryTtlEvictsAfterAccessExpiry() throws InterruptedException {
        MemoryStoreProperties props = shortTtlProps();
        InMemoryContextArtifactRepository repo = new InMemoryContextArtifactRepository(props);

        repo.save(artifact("art-1", "root-1"));
        Thread.sleep(1200);
        repo.save(artifact("art-2", "root-1"));

        assertFalse(repo.findByArtifactIdAndRootRunId("art-1", "root-1").isPresent());
        assertTrue(repo.findByArtifactIdAndRootRunId("art-2", "root-1").isPresent());
    }

    @Test
    public void contextBlobStoreTtlEvictsAfterAccessExpiry() throws InterruptedException {
        MemoryStoreProperties props = shortTtlProps();
        InMemoryContextBlobStore store = new InMemoryContextBlobStore(props);

        store.write("root-1", "blob-1", "content-1");
        Thread.sleep(1200);
        store.write("root-1", "blob-2", "content-2");

        assertTrue(store.read("memory://root-1/blob-1").isEmpty());
        assertFalse(store.read("memory://root-1/blob-2").isEmpty());
    }

    @Test
    public void frequentAccessKeepsEntryAlive() throws InterruptedException {
        MemoryStoreProperties props = new MemoryStoreProperties();
        props.setTtlSeconds(1);
        props.setMaxRuns(100);
        InMemoryAgentRunRepository repo = new InMemoryAgentRunRepository(props);

        repo.save(run("run-1"));
        // Access every 500ms, staying under the 1s TTL
        for (int i = 0; i < 5; i++) {
            Thread.sleep(500);
            assertTrue(repo.find("run-1").isPresent());
        }
        // Should still be present because we kept accessing it
        assertTrue(repo.find("run-1").isPresent());
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
        approval.setExpiresAt(Instant.now().plusSeconds(3600));
        return approval;
    }

    private static cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact artifact(String artifactId, String rootRunId) {
        cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact artifact =
                new cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact();
        artifact.setArtifactId(artifactId);
        artifact.setRootRunId(rootRunId);
        artifact.setCreatedAt(Instant.now());
        return artifact;
    }
}
