package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.*;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHook;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookRegistry;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryStoreProperties;
import cn.lunalhx.ai.domain.agent.service.context.ContextWindowManager;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationExecutionGuard;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopFactory;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopRuntimeDependencies;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopStateDependencies;
import cn.lunalhx.ai.domain.agent.service.subagent.RoleToolRegistryFactory;
import cn.lunalhx.ai.domain.agent.service.subagent.SubAgentCoordinator;
import cn.lunalhx.ai.domain.agent.service.undo.UndoSessionCoordinator;
import cn.lunalhx.ai.domain.agent.service.undo.WorkspaceUndoService;
import cn.lunalhx.ai.domain.agent.service.workspace.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.common.LoomPaths;
import cn.lunalhx.ai.domain.memory.service.MemorySelectionService;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.infrastructure.adapter.port.InMemorySubAgentControlInbox;
import cn.lunalhx.ai.infrastructure.skill.FileSystemSkillRepository;
import cn.lunalhx.ai.runtime.hook.CheckpointAgentHook;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

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
                                                                      ContextWindowManager contexts,
                                                                      ToolOutputSanitizer sanitizer,
                                                                      ModelRuntimeProperties model,
                                                                      AgentRuntimeConfigRegistry snapshots) {
        return new AgentLoopRuntimeDependencies(agent, traces, budgets, metrics, contexts,
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

    @Bean
    public SubAgentControlInbox subAgentControlInbox(MemoryStoreProperties properties) {
        return new InMemorySubAgentControlInbox(properties);
    }

    @Bean
    public SkillRepository skillRepository(AgentRuntimeProperties properties,
                                           ExtensionsConfigRegistry extensions) {
        var skill = properties.getSkills();
        Path userDir = skill != null && skill.getUserDir() != null ? Path.of(skill.getUserDir()) : null;
        String projectDir = skill != null && skill.getProjectDir() != null
                ? skill.getProjectDir() : ".agents/skills";
        return new FileSystemSkillRepository(userDir, projectDir,
                () -> extensions.capture().config().getSkills());
    }

    @Bean
    public SubAgentCoordinator subAgentCoordinator(RoleToolRegistryFactory roles,
                                                   AgentLoopFactory factory,
                                                   AgentRuntimeProperties properties,
                                                   ObjectMapper mapper,
                                                   ThreadPoolExecutor executor,
                                                   SubAgentControlInbox inbox) {
        return new SubAgentCoordinator(roles, factory, properties, mapper, executor, inbox);
    }

    @Bean
    public UndoSessionCoordinator undoSessionCoordinator(WorkspaceSnapshotPort snapshots,
                                                          UndoSnapshotRepository undoSnapshots,
                                                          WorkspaceUndoLockRepository locks,
                                                          AgentWorkspaceResolver workspaces,
                                                          AgentRuntimeProperties properties) {
        return new UndoSessionCoordinator(snapshots, undoSnapshots, locks, workspaces, properties.getUndo());
    }

    @Bean
    public WorkspaceUndoService workspaceUndoService(UndoSnapshotRepository undoSnapshots,
                                                       WorkspaceUndoLockRepository locks,
                                                       WorkspaceSnapshotPort snapshots,
                                                       AgentRunRepository runs,
                                                       AgentWorkspaceResolver workspaces,
                                                       AgentRuntimeProperties properties) {
        return new WorkspaceUndoService(undoSnapshots, locks, snapshots, runs, workspaces, properties.getUndo());
    }

    @Bean
    @ConditionalOnMissingBean(MemorySelectionService.class)
    public MemorySelectionService fallbackMemorySelectionService() {
        return new MemorySelectionService(null, null, 0, 0, 0.35, 4) {
            @Override
            public SelectionResult select(String workspace, String question) {
                return SelectionResult.EMPTY;
            }

            @Override
            public String renderWrappedText(SelectionResult result) {
                return "";
            }
        };
    }
}
