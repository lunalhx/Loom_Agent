package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;

public record AgentLoopComponents(
        AgentContextFactory contextFactory,
        AgentResumeCoordinator resumeCoordinator,
        AgentNodeLifecycle nodeLifecycle,
        AgentEventFactory eventFactory,
        AgentRunRepository runRepository,
        AgentCheckpointRepository checkpointRepository
) {}
