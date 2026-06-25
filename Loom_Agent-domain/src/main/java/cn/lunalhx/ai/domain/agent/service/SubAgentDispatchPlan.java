package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.SubAgentTask;

import java.util.List;

record SubAgentDispatchPlan(
        String reason,
        List<SubAgentTask> tasks,
        int concurrency,
        long timeoutMs) {
}
