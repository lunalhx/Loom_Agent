package cn.lunalhx.ai.domain.agent.service.subagent;

import cn.lunalhx.ai.domain.agent.model.entity.SubAgentTask;

import java.util.List;

record SubAgentDispatchPlan(
        String reason,
        List<SubAgentTask> tasks,
        int concurrency,
        long timeoutMs) {
}
