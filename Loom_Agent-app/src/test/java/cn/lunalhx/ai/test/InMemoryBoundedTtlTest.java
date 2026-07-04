package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.valobj.MemoryStoreProperties;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentRunRepository;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class InMemoryBoundedTtlTest {

    @Test
    public void frequentAccessKeepsEntryAlive() throws InterruptedException {
        MemoryStoreProperties props = new MemoryStoreProperties();
        props.setTtlSeconds(1);
        props.setMaxRuns(100);
        InMemoryAgentRunRepository repo = new InMemoryAgentRunRepository(props);

        repo.save(run("run-1"));
        for (int i = 0; i < 5; i++) {
            Thread.sleep(500);
            assertTrue(repo.find("run-1").isPresent());
        }
        assertTrue(repo.find("run-1").isPresent());
    }

    private static cn.lunalhx.ai.domain.agent.model.entity.AgentRun run(String runId) {
        cn.lunalhx.ai.domain.agent.model.entity.AgentRun run =
                new cn.lunalhx.ai.domain.agent.model.entity.AgentRun();
        run.setRunId(runId);
        return run;
    }
}
