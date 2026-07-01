package cn.lunalhx.ai.domain.agent.service.subagent;

import cn.lunalhx.ai.domain.agent.model.entity.SubAgentTask;

import java.util.List;

record SubAgentDispatchRequest(
        String reason,
        int requestedConcurrency,
        List<SubAgentTask> tasks,
        String errorCode,
        String errorMessage) {
}
