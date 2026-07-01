package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.service.context.AgentContextFactory;

public record AgentLoopComponents(
        AgentContextFactory contextFactory,
        AgentResumeCoordinator resumeCoordinator,
        AgentNodeLifecycle nodeLifecycle,
        AgentEventFactory eventFactory,
        AgentRunRepository runRepository,
        AgentCheckpointRepository checkpointRepository
) {}
