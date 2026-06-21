package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;

import java.util.Optional;

public interface AgentCheckpointRepository {

    AgentCheckpoint save(AgentCheckpoint checkpoint);

    Optional<AgentCheckpoint> latest(String runId);

}
