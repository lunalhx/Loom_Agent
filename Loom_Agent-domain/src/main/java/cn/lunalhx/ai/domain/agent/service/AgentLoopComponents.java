package cn.lunalhx.ai.domain.agent.service;

public record AgentLoopComponents(
        AgentContextFactory contextFactory,
        AgentResumeCoordinator resumeCoordinator,
        AgentNodeLifecycle nodeLifecycle,
        AgentEventFactory eventFactory
) {}
