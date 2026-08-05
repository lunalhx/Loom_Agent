package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationExecutionGuard;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopFactory;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopRuntimeDependencies;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopStateDependencies;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
public class AgentLoopAutoConfig {

    @Bean
    public AgentLoopFactory agentLoopFactory(ModelGateway modelGateway,
                                             AgentLoopStateDependencies state,
                                             AgentLoopRuntimeDependencies runtime,
                                             ConversationHistoryAppendService ledgerAppendService,
                                             ContextManager contextManager,
                                             ConversationExecutionGuard executionGuard) {
        return new AgentLoopFactory(modelGateway, state, runtime, ledgerAppendService,
                contextManager, executionGuard);
    }

    @Bean
    public AgentLoopService agentLoopService(AgentLoopFactory factory,
                                             ToolRegistry registry,
                                             ThreadPoolExecutor executor) {
        return factory.createStandalone(registry, executor);
    }
}
