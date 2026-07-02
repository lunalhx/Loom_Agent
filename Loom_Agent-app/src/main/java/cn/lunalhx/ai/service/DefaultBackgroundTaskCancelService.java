package cn.lunalhx.ai.service;

import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
import cn.lunalhx.ai.domain.tool.model.BackgroundShellTask;
import cn.lunalhx.ai.domain.tool.model.BackgroundTaskStatus;
import cn.lunalhx.ai.domain.tool.service.BackgroundTaskCancelService;
import cn.lunalhx.ai.infrastructure.tool.BackgroundProcessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultBackgroundTaskCancelService implements BackgroundTaskCancelService {

    private static final Logger log = LoggerFactory.getLogger(DefaultBackgroundTaskCancelService.class);

    private final BackgroundShellTaskRepository taskRepository;
    private final BackgroundProcessManager processManager;

    public DefaultBackgroundTaskCancelService(BackgroundShellTaskRepository taskRepository,
                                               BackgroundProcessManager processManager) {
        this.taskRepository = taskRepository;
        this.processManager = processManager;
    }

    @Override
    public CancelResult cancel(String runId, String taskId) {
        BackgroundShellTask task = taskRepository.find(taskId).orElse(null);
        if (task == null || !runId.equals(task.getRunId())) {
            return new CancelResult(false, null, "BACKGROUND_TASK_NOT_FOUND");
        }
        if (task.isTerminal()) {
            return new CancelResult(true, task.getStatus(), null);
        }

        boolean cancelled = processManager.cancelProcess(runId, taskId);
        if (cancelled) {
            task = taskRepository.find(taskId).orElse(task);
            return new CancelResult(true, BackgroundTaskStatus.CANCELLED, null);
        }

        task = taskRepository.find(taskId).orElse(task);
        if (task.isTerminal()) {
            return new CancelResult(true, task.getStatus(), null);
        }
        return new CancelResult(false, null, "BACKGROUND_TASK_CANCEL_FAILED");
    }

    @Override
    public void cancelAllForRun(String runId) {
        processManager.cancelAllProcessesForRun(runId);
    }

}
