package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;

import java.util.Objects;

/**
 * Agent Loop 运行策略、观测和上下文管理相关依赖。
 *
 * <p>Phase 2 依赖分组：新生产路径只允许通过此 record 传递运行时依赖。
 * {@code Executor} 不放入此 record（父/子 Agent 使用不同执行方式）。
 *
 * @see AgentLoopStateDependencies
 */
public record AgentLoopRuntimeDependencies(
        AgentRuntimeProperties properties,
        TraceRecorder traceRecorder,
        BudgetGuard budgetGuard,
        AgentMetrics agentMetrics,
        ContextWindowManager contextWindowManager
) {
    public AgentLoopRuntimeDependencies {
        Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(traceRecorder, "traceRecorder must not be null");
        Objects.requireNonNull(budgetGuard, "budgetGuard must not be null");
        Objects.requireNonNull(agentMetrics, "agentMetrics must not be null");
        Objects.requireNonNull(contextWindowManager, "contextWindowManager must not be null");
    }
}
