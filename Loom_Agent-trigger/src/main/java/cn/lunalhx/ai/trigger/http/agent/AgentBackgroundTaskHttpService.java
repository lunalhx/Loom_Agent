package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.BackgroundTaskDetailResponse;
import cn.lunalhx.ai.api.dto.BackgroundTaskResponse;
import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
import cn.lunalhx.ai.domain.tool.adapter.port.TaskLogReader;
import cn.lunalhx.ai.domain.tool.model.BackgroundShellTask;
import cn.lunalhx.ai.domain.tool.model.LogChunk;
import cn.lunalhx.ai.domain.tool.service.BackgroundTaskCancelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBackgroundTaskHttpService {

    private final BackgroundShellTaskRepository taskRepository;
    private final BackgroundTaskCancelService cancelService;
    private final TaskLogReader logReader;

    public List<BackgroundTaskResponse> listTasks(String runId) {
        List<BackgroundShellTask> tasks = taskRepository.findByRunId(runId);
        return tasks.stream()
                .map(t -> BackgroundTaskResponse.builder()
                        .taskId(t.getTaskId())
                        .runId(t.getRunId())
                        .conversationId(t.getConversationId())
                        .workspace(t.getWorkspace())
                        .command(t.getCommand())
                        .cwd(t.getCwd())
                        .launchMode(t.getLaunchMode() == null ? null : t.getLaunchMode().name())
                        .timeoutMs(t.getTimeoutMs())
                        .pid(t.getPid())
                        .status(t.getStatus() == null ? null : t.getStatus().name())
                        .exitCode(t.getExitCode())
                        .errorCode(t.getErrorCode())
                        .errorMessage(t.getErrorMessage())
                        .stdoutBytes(t.getStdoutBytes())
                        .stderrBytes(t.getStderrBytes())
                        .startedAt(t.getStartedAt() == null ? null : t.getStartedAt().toString())
                        .completedAt(t.getCompletedAt() == null ? null : t.getCompletedAt().toString())
                        .completionNotified(t.isCompletionNotified())
                        .build())
                .toList();
    }

    public BackgroundTaskDetailResponse getTaskDetail(String runId, String taskId,
                                                       long stdoutOffset, long stderrOffset,
                                                       int limitBytes) {
        BackgroundShellTask task = taskRepository.find(taskId).orElse(null);
        if (task == null || !runId.equals(task.getRunId())) {
            return null;
        }

        if (stdoutOffset < 0 || stderrOffset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (limitBytes < TaskLogReader.MIN_LIMIT_BYTES || limitBytes > TaskLogReader.MAX_LIMIT_BYTES) {
            throw new IllegalArgumentException("limitBytes must be [" + TaskLogReader.MIN_LIMIT_BYTES + ", " + TaskLogReader.MAX_LIMIT_BYTES + "]");
        }

        LogChunk stdoutChunk = readChunkSafe(task.getStdoutFile(), stdoutOffset, limitBytes);
        LogChunk stderrChunk = readChunkSafe(task.getStderrFile(), stderrOffset, limitBytes);

        long stdoutEnd = stdoutChunk != null ? stdoutChunk.getNextOffset() : stdoutOffset;
        long stderrEnd = stderrChunk != null ? stderrChunk.getNextOffset() : stderrOffset;

        return BackgroundTaskDetailResponse.builder()
                .taskId(task.getTaskId())
                .runId(task.getRunId())
                .status(task.getStatus() == null ? null : task.getStatus().name())
                .exitCode(task.getExitCode())
                .errorCode(task.getErrorCode())
                .errorMessage(task.getErrorMessage())
                .stdoutChunk(stdoutChunk != null ? stdoutChunk.getContent() : null)
                .stderrChunk(stderrChunk != null ? stderrChunk.getContent() : null)
                .stdoutOffset(stdoutEnd)
                .stderrOffset(stderrEnd)
                .stdoutEof(stdoutChunk != null ? stdoutChunk.isEof() : true)
                .stderrEof(stderrChunk != null ? stderrChunk.isEof() : true)
                .stdoutBytes(stdoutChunk != null ? stdoutChunk.getTotalBytes() : 0)
                .stderrBytes(stderrChunk != null ? stderrChunk.getTotalBytes() : 0)
                .command(task.getCommand())
                .cwd(task.getCwd())
                .launchMode(task.getLaunchMode() == null ? null : task.getLaunchMode().name())
                .timeoutMs(task.getTimeoutMs())
                .build();
    }

    public BackgroundTaskResponse cancelTask(String runId, String taskId) {
        BackgroundTaskCancelService.CancelResult result = cancelService.cancel(runId, taskId);

        if (!result.success() && "BACKGROUND_TASK_NOT_FOUND".equals(result.errorCode())) {
            return null;
        }
        if (!result.success()) {
            return BackgroundTaskResponse.builder()
                    .taskId(taskId)
                    .runId(runId)
                    .status("CANCEL_FAILED")
                    .errorCode(result.errorCode())
                    .build();
        }

        BackgroundShellTask task = taskRepository.find(taskId).orElse(null);
        return BackgroundTaskResponse.builder()
                .taskId(taskId)
                .runId(runId)
                .status(result.status() == null ? null : result.status().name())
                .exitCode(task != null ? task.getExitCode() : null)
                .errorCode(task != null ? task.getErrorCode() : null)
                .errorMessage(task != null ? task.getErrorMessage() : null)
                .stdoutBytes(task != null ? task.getStdoutBytes() : 0)
                .stderrBytes(task != null ? task.getStderrBytes() : 0)
                .startedAt(task != null && task.getStartedAt() != null ? task.getStartedAt().toString() : null)
                .completedAt(task != null && task.getCompletedAt() != null ? task.getCompletedAt().toString() : null)
                .completionNotified(task != null && task.isCompletionNotified())
                .build();
    }

    private LogChunk readChunkSafe(String filePath, long offset, int limitBytes) {
        if (filePath == null) {
            return null;
        }
        Path path = Path.of(filePath);
        try {
            return logReader.readChunk(path, offset, limitBytes);
        } catch (Exception e) {
            log.warn("Failed to read log chunk: file={} offset={}", filePath, offset, e);
            return null;
        }
    }

}
