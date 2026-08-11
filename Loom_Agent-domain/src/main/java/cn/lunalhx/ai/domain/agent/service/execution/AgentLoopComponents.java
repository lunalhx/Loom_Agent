package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.PlanSubmissionHandler;
import cn.lunalhx.ai.domain.agent.service.context.AgentContextFactory;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;

public record AgentLoopComponents(
        AgentContextFactory contextFactory,
        AgentNodeLifecycle nodeLifecycle,
        AgentEventFactory eventFactory,
        AgentRunRepository runRepository,
        AgentCheckpointRepository checkpointRepository,
        AgentRunLifecycle lifecycle,
        ConversationHistoryAppendService ledgerAppendService,
        PlanSubmissionHandler planSubmissionHandler
) {}
