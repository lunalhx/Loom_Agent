package cn.lunalhx.ai.runtime.hook;

import cn.lunalhx.ai.domain.agent.flow.hook.AgentHook;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerInitializer;
import cn.lunalhx.ai.domain.tool.model.BackgroundShellTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Order(350)
public class BackgroundTaskCompletionHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskCompletionHook.class);

    private final BackgroundShellTaskRepository taskRepository;
    private final ConversationLedgerAppendService ledgerAppendService;

    public BackgroundTaskCompletionHook(BackgroundShellTaskRepository taskRepository,
                                         ConversationLedgerAppendService ledgerAppendService) {
        this.taskRepository = taskRepository;
        this.ledgerAppendService = ledgerAppendService;
    }

    @Override
    public AgentHookResult onEvent(AgentHookEvent event, AgentHookContext hookContext) {
        if (event != AgentHookEvent.AFTER_NODE) {
            return AgentHookResult.proceed();
        }
        AgentContext context = hookContext.getAgentContext();
        if (context == null || context.getRunId() == null) {
            return AgentHookResult.proceed();
        }

        List<BackgroundShellTask> tasks = taskRepository.findByRunId(context.getRunId());
        List<AgentEvent> events = new ArrayList<>();

        for (BackgroundShellTask task : tasks) {
            if (task.isTerminal() && !task.isCompletionNotified()) {
                boolean marked = taskRepository.markNotified(task.getTaskId());
                if (!marked) {
                    continue;
                }

                log.info("Injecting background task completion: taskId={} status={}",
                        task.getTaskId(), task.getStatus());

                String observation = buildCompletionObservation(task);
                if (ledgerAppendService != null) {
                    ledgerAppendService.appendSystemNote(context, observation,
                            ConversationLedgerInitializer.eventKey(context.getRunId(),
                                    String.valueOf(Math.max(1, context.getStep())),
                                    "background_task_completion:" + task.getTaskId()));
                }

                AgentEventType sseType = switch (task.getStatus()) {
                    case SUCCEEDED -> AgentEventType.BACKGROUND_TASK_COMPLETED;
                    case CANCELLED -> AgentEventType.BACKGROUND_TASK_CANCELLED;
                    default -> AgentEventType.BACKGROUND_TASK_FAILED;
                };

                events.add(AgentEvent.builder()
                        .type(sseType)
                        .runId(context.getRunId())
                        .requestId(context.getRequestId())
                        .conversationId(context.getConversationId())
                        .workspace(context.getWorkspaceDisplayName())
                        .step(context.getStep())
                        .thought(observation)
                        .build());
            }
        }

        return AgentHookResult.proceed(events);
    }

    private String buildCompletionObservation(BackgroundShellTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("[后台任务完成]\n");
        sb.append("task_id: ").append(task.getTaskId()).append("\n");
        sb.append("command: ").append(task.getCommand()).append("\n");
        sb.append("status: ").append(task.getStatus()).append("\n");
        if (task.getExitCode() != null) {
            sb.append("exit_code: ").append(task.getExitCode()).append("\n");
        }
        if (task.getErrorCode() != null) {
            sb.append("error_code: ").append(task.getErrorCode()).append("\n");
        }
        if (task.getErrorMessage() != null) {
            sb.append("error_message: ").append(task.getErrorMessage()).append("\n");
        }
        sb.append("stdout_bytes: ").append(task.getStdoutBytes()).append("\n");
        sb.append("stderr_bytes: ").append(task.getStderrBytes()).append("\n");
        sb.append("\n请使用 shell_task poll 读取输出");
        return sb.toString();
    }

}
