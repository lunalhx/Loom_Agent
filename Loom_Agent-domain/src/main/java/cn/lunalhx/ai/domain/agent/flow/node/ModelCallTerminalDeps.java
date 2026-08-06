package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;

import java.util.Objects;

/**
 * Groups terminal dependencies for {@link ModelCallNode} so the constructor
 * stays within the 5-parameter architecture limit.
 */
public final class ModelCallTerminalDeps {

    private final ModelGateway modelGateway;
    private final BudgetGuard budgetGuard;
    private final TraceRecorder traceRecorder;
    private final AgentMetrics agentMetrics;

    public ModelCallTerminalDeps(ModelGateway modelGateway,
                                  BudgetGuard budgetGuard,
                                  TraceRecorder traceRecorder) {
        this(modelGateway, budgetGuard, traceRecorder, null);
    }

    public ModelCallTerminalDeps(ModelGateway modelGateway,
                                  BudgetGuard budgetGuard,
                                  TraceRecorder traceRecorder,
                                  AgentMetrics agentMetrics) {
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway must not be null");
        this.budgetGuard = budgetGuard;
        this.traceRecorder = traceRecorder;
        this.agentMetrics = agentMetrics;
    }

    public ModelGateway modelGateway() { return modelGateway; }
    public BudgetGuard budgetGuard() { return budgetGuard; }
    public TraceRecorder traceRecorder() { return traceRecorder; }
    public AgentMetrics agentMetrics() { return agentMetrics; }
}
