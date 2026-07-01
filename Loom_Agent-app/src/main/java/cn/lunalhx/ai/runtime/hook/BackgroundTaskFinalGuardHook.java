package cn.lunalhx.ai.runtime.hook;

import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHook;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookAction;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
import cn.lunalhx.ai.domain.tool.model.BackgroundShellTask;
import cn.lunalhx.ai.infrastructure.tool.BackgroundProcessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Order(100)
public class BackgroundTaskFinalGuardHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskFinalGuardHook.class);
    private static final int MAX_IGNORED_ATTEMPTS = 3;

    private final BackgroundShellTaskRepository taskRepository;
    private final BackgroundProcessManager processManager;

    public BackgroundTaskFinalGuardHook(BackgroundShellTaskRepository taskRepository,
                                         BackgroundProcessManager processManager) {
        this.taskRepository = taskRepository;
        this.processManager = processManager;
    }

    @Override
    public AgentHookResult onEvent(AgentHookEvent event, AgentHookContext context) {
        if (event != AgentHookEvent.STOP) {
            return AgentHookResult.proceed();
        }

        AgentContext agentContext = context.getAgentContext();
        if (agentContext == null || agentContext.getRunId() == null) {
            return AgentHookResult.proceed();
        }

        if (!AgentNodeNames.FINAL_ANSWER.equals(context.getNode())) {
            return AgentHookResult.proceed();
        }

        if (agentContext.getStopReason() != AgentStopReason.FINAL_ANSWER) {
            return AgentHookResult.proceed();
        }

        List<BackgroundShellTask> running = taskRepository.findRunningByRunId(agentContext.getRunId());
        if (running.isEmpty()) {
            return AgentHookResult.proceed();
        }

        int continuationCount = agentContext.getStopHookContinuationCount();
        int maxContinuations = MAX_IGNORED_ATTEMPTS - 1;

        if (continuationCount < maxContinuations) {
            agentContext.setStopHookContinuationCount(continuationCount + 1);

            StringBuilder sb = new StringBuilder();
            sb.append("还有 ").append(running.size()).append(" 个后台任务正在运行，不能输出最终回答。\n\n");
            sb.append("运行中的任务:\n");
            for (BackgroundShellTask t : running) {
                sb.append("- task_id: ").append(t.getTaskId())
                        .append(" command: ").append(t.getCommand())
                        .append(" status: ").append(t.getStatus()).append("\n");
            }
            sb.append("\n请使用 shell_task poll 等待任务完成并读取输出，或使用 shell_task cancel 取消不需要的任务。\n");
            sb.append("连续 ").append(MAX_IGNORED_ATTEMPTS).append(" 次无视此提示将强制失败结束。");

            agentContext.getDynamicText().appendSystemNote(
                    agentContext.getStep(),
                    AgentNodeNames.FINAL_ANSWER,
                    "Stop Hook: 后台任务未完成",
                    sb.toString());

            AgentEvent hookEvent = AgentEvent.builder()
                    .type(AgentEventType.STOP_HOOK_RESULT)
                    .runId(agentContext.getRunId())
                    .requestId(agentContext.getRequestId())
                    .conversationId(agentContext.getConversationId())
                    .workspace(agentContext.getWorkspaceDisplayName())
                    .node(AgentNodeNames.FINAL_ANSWER)
                    .step(agentContext.getStep())
                    .metadata(Map.of(
                            "hook", "background_task_guard",
                            "decision", "continued",
                            "reason", "background_tasks_running",
                            "nextNode", AgentNodeNames.OBSERVATION,
                            "attempt", continuationCount + 1,
                            "maxAttempts", maxContinuations,
                            "runningTasks", running.size()))
                    .build();

            return AgentHookResult.interrupt(
                    AgentHookAction.continueAt(AgentNodeNames.OBSERVATION,
                            "background_tasks_running: " + running.size(), true),
                    List.of(hookEvent));
        }

        // Max attempts exceeded: force fail
        log.warn("Agent run={} ignored {} stop-hook warnings, force-finalizing with unresolved tasks",
                agentContext.getRunId(), MAX_IGNORED_ATTEMPTS);
        running.forEach(t -> {
            processManager.cancel(t.getRunId(), t.getTaskId());
            t.setStatus(cn.lunalhx.ai.domain.tool.model.BackgroundTaskStatus.CANCELLED);
            t.setCompletedAt(java.time.Instant.now());
            taskRepository.save(t);
        });

        agentContext.setStopReason(AgentStopReason.TOOL_ERROR);
        agentContext.setErrorCode("background_tasks_unresolved");
        agentContext.setErrorMessage("结束前仍有 " + running.size() + " 个后台任务未解决，已强制取消");

        AgentEvent bypassEvent = AgentEvent.builder()
                .type(AgentEventType.STOP_HOOK_RESULT)
                .runId(agentContext.getRunId())
                .requestId(agentContext.getRequestId())
                .conversationId(agentContext.getConversationId())
                .workspace(agentContext.getWorkspaceDisplayName())
                .node(AgentNodeNames.FINAL_ANSWER)
                .step(agentContext.getStep())
                .metadata(Map.of(
                        "hook", "background_task_guard",
                        "decision", "force_failed",
                        "reason", "max_continuations_exceeded",
                        "attempt", continuationCount + 1,
                        "maxAttempts", maxContinuations,
                        "runningTasks", running.size()))
                .build();

        return AgentHookResult.proceed(List.of(bypassEvent));
    }

}
