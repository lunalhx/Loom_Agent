package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentRunRepository;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class InMemoryBoundedCapacityTest {

    @Test
    public void noArgConstructorIsUnbounded() {
        InMemoryAgentRunRepository repo = new InMemoryAgentRunRepository();
        for (int i = 0; i < 2000; i++) {
            AgentRun run = new AgentRun();
            run.setRunId("run-" + i);
            repo.save(run);
        }
        assertTrue(repo.find("run-0").isPresent());
        assertTrue(repo.find("run-1999").isPresent());
    }

}
