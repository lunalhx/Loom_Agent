package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookRegistry;
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
import cn.lunalhx.ai.infrastructure.gateway.ChatModelFactoryRegistry;
import cn.lunalhx.ai.infrastructure.gateway.DeepSeekChatModelFactory;
import cn.lunalhx.ai.infrastructure.gateway.OpenCodeGoChatModelFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
public class AgentLoopAutoConfig {

    @Bean
    public AgentLoopFactory agentLoopFactory(ModelGateway modelGateway,
                                             AgentLoopStateDependencies state,
                                             AgentLoopRuntimeDependencies runtime,
                                             AgentHookRegistry hookRegistry,
                                             ConversationHistoryAppendService ledgerAppendService,
                                             ContextManager contextManager,
                                             ConversationExecutionGuard executionGuard) {
        return new AgentLoopFactory(modelGateway, state, runtime, hookRegistry,
                ledgerAppendService, contextManager, executionGuard);
    }

    @Bean
    public AgentLoopService agentLoopService(AgentLoopFactory factory,
                                             ToolRegistry registry,
                                             ThreadPoolExecutor executor) {
        return factory.createStandalone(registry, executor);
    }

    @Bean
    public InitializingBean aiConfigValidator(ModelRuntimeProperties model,
                                               AgentRuntimeProperties agent,
                                               StreamRequestLimitProperties stream,
                                               Environment environment,
                                               ThreadPoolExecutor executor,
                                               ChatModelFactoryRegistry factories) {
        return () -> StartupConfigValidator.validate(model, agent, stream, environment, executor, factories);
    }

    public InitializingBean aiConfigValidator(ModelRuntimeProperties model,
                                               AgentRuntimeProperties agent,
                                               StreamRequestLimitProperties stream,
                                               Environment environment,
                                               ThreadPoolExecutor executor) {
        return aiConfigValidator(model, agent, stream, environment, executor,
                new ChatModelFactoryRegistry(List.of(
                        new DeepSeekChatModelFactory(), new OpenCodeGoChatModelFactory())));
    }
}
