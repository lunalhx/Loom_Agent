package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;

import java.util.Optional;

public interface AgentRunRepository {

    AgentRun save(AgentRun run);

    Optional<AgentRun> find(String runId);

}
