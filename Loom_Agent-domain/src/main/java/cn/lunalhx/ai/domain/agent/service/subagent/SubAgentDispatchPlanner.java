package cn.lunalhx.ai.domain.agent.service.subagent;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentTask;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;

import java.util.List;

class SubAgentDispatchPlanner {

    private final AgentRuntimeProperties properties;
    private final SubAgentDecisionParser parser;

    SubAgentDispatchPlanner(AgentRuntimeProperties properties, SubAgentDecisionParser parser) {
        this.properties = properties;
        this.parser = parser;
    }

    SubAgentPlanResult plan(AgentContext parent) {
        if (!Boolean.TRUE.equals(properties.getSubAgentEnabled())) {
            return SubAgentPlanResult.error("sub_agent_disabled", "子 Agent 功能未启用");
        }
        if (parent.getAgentDepth() >= positive(properties.getSubAgentMaxDepth(), 1)) {
            return SubAgentPlanResult.error("sub_agent_depth_exceeded", "已达到子 Agent 最大派生深度");
        }

        SubAgentDispatchRequest request = parser.parse(parent.getDecision());
        if (request.errorCode() != null) {
            return SubAgentPlanResult.error(request.errorCode(), request.errorMessage());
        }
        if (request.tasks().size() > positive(properties.getSubAgentMaxChildren(), 6)) {
            return SubAgentPlanResult.error("sub_agent_too_many_tasks", "子任务数量超过上限");
        }
        if (containsEditor(request.tasks()) && (request.tasks().size() > 1 || request.requestedConcurrency() > 1)) {
            return SubAgentPlanResult.error("sub_agent_editor_parallel_denied",
                    "编辑型子 Agent 只能单个串行执行");
        }

        int concurrency = Math.max(1, Math.min(request.requestedConcurrency(),
                positive(properties.getSubAgentMaxConcurrency(), 4)));
        long timeoutMs = positive(properties.getSubAgentTimeoutMs(), 60000L);

        return SubAgentPlanResult.success(new SubAgentDispatchPlan(
                request.reason(), request.tasks(), concurrency, timeoutMs));
    }

    private boolean containsEditor(List<SubAgentTask> tasks) {
        return tasks.stream().anyMatch(task -> task.getRole() == AgentRole.EDITOR);
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private long positive(Long value, long fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
