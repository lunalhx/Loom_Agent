package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.budget.DefaultBudgetGuard;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AgentContextAutoConfig {

    @Bean
    public BudgetGuard budgetGuard(AgentRuntimeProperties agent,
                                   ModelRuntimeProperties model) {
        return new DefaultBudgetGuard(agent, model);
    }

    @Bean
    public ContextManager contextManager(AgentRuntimeProperties agent) {
        return new ContextManager(agent);
    }

    @Bean
    public ConversationHistoryAppendService conversationHistoryAppendService(
            AgentRuntimeProperties properties) {
        return new ConversationHistoryAppendService(properties);
    }
}
