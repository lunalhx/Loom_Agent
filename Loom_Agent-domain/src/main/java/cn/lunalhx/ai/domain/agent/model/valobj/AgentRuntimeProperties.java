package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;
import lombok.experimental.Delegate;

@Data
public class AgentRuntimeProperties {
    @Delegate
    private AgentCoreProperties core = new AgentCoreProperties();
    private BudgetProperties budget = new BudgetProperties();
    private ContextProperties context = new ContextProperties();
    private ModelRecoveryProperties modelRecovery = new ModelRecoveryProperties();
    private String approvalPolicy = "ask";

    public synchronized void replaceFrom(AgentRuntimeProperties replacement) {
        this.core = replacement.core;
        this.budget = replacement.budget;
        this.context = replacement.context;
        this.modelRecovery = replacement.modelRecovery;
        this.approvalPolicy = replacement.approvalPolicy;
    }
}