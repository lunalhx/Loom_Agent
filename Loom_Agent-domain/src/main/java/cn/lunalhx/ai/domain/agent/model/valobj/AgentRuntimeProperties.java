package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;
import lombok.experimental.Delegate;

@Data
public class AgentRuntimeProperties {
    @Delegate
    private AgentCoreProperties core = new AgentCoreProperties();
    private BudgetProperties budget = new BudgetProperties();
    private ShellCommandProperties shellCommands = new ShellCommandProperties();
    private ContextProperties context = new ContextProperties();
    private StopHooksProperties stopHooks = new StopHooksProperties();
    private ExecutionGuardProperties executionGuards = new ExecutionGuardProperties();
    private ModelRecoveryProperties modelRecovery = new ModelRecoveryProperties();
    private StepBudgetProperties stepBudget = new StepBudgetProperties();
    private UndoProperties undo = new UndoProperties();
    private ConversationLedgerProperties conversationLedger = new ConversationLedgerProperties();
    private SkillProperties skills = new SkillProperties();
    private BackgroundShellProperties backgroundShell = new BackgroundShellProperties();
    private SandboxProperties sandbox = new SandboxProperties();
    private MemoryRuntimeProperties longTermMemory = new MemoryRuntimeProperties();

    public synchronized void replaceFrom(AgentRuntimeProperties replacement) {
        this.core = replacement.core;
        this.budget = replacement.budget;
        this.shellCommands = replacement.shellCommands;
        this.context = replacement.context;
        this.stopHooks = replacement.stopHooks;
        this.executionGuards = replacement.executionGuards;
        this.modelRecovery = replacement.modelRecovery;
        this.stepBudget = replacement.stepBudget;
        this.undo = replacement.undo;
        this.conversationLedger = replacement.conversationLedger;
        this.skills = replacement.skills;
        this.backgroundShell = replacement.backgroundShell;
        this.sandbox = replacement.sandbox;
        this.longTermMemory = replacement.longTermMemory;
    }
}
