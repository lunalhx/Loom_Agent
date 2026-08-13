package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationHistoryRepository;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationExecutionGuard;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopRuntimeDependencies;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopStateDependencies;
import cn.lunalhx.ai.domain.agent.service.workspace.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.common.LoomPaths;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AgentExecutionAutoConfig {

    @Bean
    public AgentWorkspaceResolver agentWorkspaceResolver(AgentRuntimeProperties properties,
                                                         LoomPaths paths) {
        return new AgentWorkspaceResolver(properties, paths.userHome());
    }

    @Bean
    public ConversationExecutionGuard conversationExecutionGuard() {
        return new ConversationExecutionGuard();
    }

    @Bean
    public AgentLoopStateDependencies agentLoopStateDependencies(AgentWorkspaceResolver workspaces,
                                                                AgentRunRepository runs,
                                                                AgentCheckpointRepository checkpoints,
                                                                ConversationHistoryRepository historyRepository,
                                                                ObjectMapper mapper) {
        return new AgentLoopStateDependencies(workspaces, runs, checkpoints, historyRepository, mapper);
    }

    @Bean
    public AgentLoopRuntimeDependencies agentLoopRuntimeDependencies(AgentRuntimeProperties agent,
                                                                      cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder traces,
                                                                      cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard budgets,
                                                                      cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics metrics,
                                                                      ToolOutputSanitizer sanitizer,
                                                                      ModelRuntimeProperties model,
                                                                      AgentRuntimeConfigRegistry snapshots) {
        return new AgentLoopRuntimeDependencies(agent, traces, budgets, metrics,
                sanitizer, model, snapshots);
    }
}