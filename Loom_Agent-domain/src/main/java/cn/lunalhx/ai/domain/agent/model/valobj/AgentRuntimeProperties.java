package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;
import lombok.experimental.Delegate;

@Data
public class AgentRuntimeProperties {
    @Delegate
    private AgentCoreProperties core = new AgentCoreProperties();
    private BudgetProperties budget = new BudgetProperties();
    private ContextProperties context = new ContextProperties();
    private ExecutionGuardProperties executionGuards = new ExecutionGuardProperties();
    private ModelRecoveryProperties modelRecovery = new ModelRecoveryProperties();
    private StepBudgetProperties stepBudget = new StepBudgetProperties();
    private String approvalPolicy = "ask";

    public synchronized void replaceFrom(AgentRuntimeProperties replacement) {
        this.core = replacement.core;
        this.budget = replacement.budget;
        this.context = replacement.context;
        this.executionGuards = replacement.executionGuards;
        this.modelRecovery = replacement.modelRecovery;
        this.stepBudget = replacement.stepBudget;
        this.approvalPolicy = replacement.approvalPolicy;
    }
}
