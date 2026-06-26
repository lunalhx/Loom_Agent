package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;

import java.util.List;
import java.util.Optional;

public interface AgentRunRepository {

    AgentRun save(AgentRun run);

    Optional<AgentRun> find(String runId);

    List<AgentRun> findChildren(String parentRunId);

    Optional<AgentRun> findLatestRootByConversationId(String conversationId);

}
