package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHook;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookRegistry;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationExecutionGuard;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopFactory;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopRuntimeDependencies;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopStateDependencies;
import cn.lunalhx.ai.domain.agent.service.workspace.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.common.LoomPaths;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryApprovalStore;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisApprovalStore;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisTraceRecorder;
import cn.lunalhx.ai.infrastructure.dao.AgentPendingApprovalDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunCheckpointDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunDao;
import cn.lunalhx.ai.infrastructure.dao.AgentTraceEventDao;
import cn.lunalhx.ai.runtime.hook.CheckpointAgentHook;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationDeletionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

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
    public AgentLoopStateDependencies agentLoopStateDependencies(ApprovalStore approvals,
                                                                  AgentWorkspaceResolver workspaces,
                                                                  AgentRunRepository runs,
                                                                  AgentCheckpointRepository checkpoints,
                                                                  ObjectMapper mapper) {
        return new AgentLoopStateDependencies(approvals, workspaces, runs, checkpoints, mapper);
    }

    @Bean
    public AgentLoopRuntimeDependencies agentLoopRuntimeDependencies(AgentRuntimeProperties agent,
                                                                      TraceRecorder traces,
                                                                      BudgetGuard budgets,
                                                                      AgentMetrics metrics,
                                                                      ToolOutputSanitizer sanitizer,
                                                                      ModelRuntimeProperties model,
                                                                      AgentRuntimeConfigRegistry snapshots) {
        return new AgentLoopRuntimeDependencies(agent, traces, budgets, metrics,
                sanitizer, model, snapshots);
    }

    @Bean
    public AgentHookRegistry agentHookRegistry(List<AgentHook> hooks) {
        return new AgentHookRegistry(hooks);
    }

    @Bean
    public CheckpointAgentHook checkpointAgentHook(AgentRunRepository runs,
                                                   AgentCheckpointRepository checkpoints,
                                                   ObjectMapper mapper,
                                                   ObjectProvider<ConversationDeletionRepository> deletion) {
        return new CheckpointAgentHook(runs, checkpoints, mapper, deletion.getIfAvailable());
    }
}
