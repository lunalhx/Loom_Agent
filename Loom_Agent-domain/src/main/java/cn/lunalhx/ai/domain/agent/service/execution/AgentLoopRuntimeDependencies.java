package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRuntimeConfigSource;
import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;

import java.util.Objects;

public record AgentLoopRuntimeDependencies(
        AgentRuntimeProperties properties,
        TraceRecorder traceRecorder,
        BudgetGuard budgetGuard,
        AgentMetrics agentMetrics,
        ToolOutputSanitizer toolOutputSanitizer,
        ModelRuntimeProperties modelRuntimeProperties,
        AgentRuntimeConfigSource runtimeConfigSource
) {
    public AgentLoopRuntimeDependencies {
        Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(traceRecorder, "traceRecorder must not be null");
        Objects.requireNonNull(budgetGuard, "budgetGuard must not be null");
        Objects.requireNonNull(agentMetrics, "agentMetrics must not be null");
        Objects.requireNonNull(toolOutputSanitizer, "toolOutputSanitizer must not be null");
        Objects.requireNonNull(modelRuntimeProperties, "modelRuntimeProperties must not be null");
        Objects.requireNonNull(runtimeConfigSource, "runtimeConfigSource must not be null");
    }

    public AgentLoopRuntimeDependencies(AgentRuntimeProperties properties,
                                        TraceRecorder traceRecorder,
                                        BudgetGuard budgetGuard,
                                        AgentMetrics agentMetrics,
                                        ToolOutputSanitizer toolOutputSanitizer,
                                        ModelRuntimeProperties modelRuntimeProperties) {
        this(properties, traceRecorder, budgetGuard, agentMetrics, toolOutputSanitizer,
                modelRuntimeProperties,
                () -> cn.lunalhx.ai.domain.agent.model.valobj.AgentRunConfig.startup(
                        properties, modelRuntimeProperties));
    }
}
