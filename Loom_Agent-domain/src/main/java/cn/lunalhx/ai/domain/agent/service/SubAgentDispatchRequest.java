package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.SubAgentTask;

import java.util.List;

record SubAgentDispatchRequest(
        String reason,
        int requestedConcurrency,
        List<SubAgentTask> tasks,
        String errorCode,
        String errorMessage) {
}
