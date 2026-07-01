package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.BackgroundTaskDetailResponse;
import cn.lunalhx.ai.api.dto.BackgroundTaskResponse;
import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
import cn.lunalhx.ai.domain.tool.model.BackgroundShellTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBackgroundTaskHttpService {

    private final BackgroundShellTaskRepository taskRepository;

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

        String stdoutChunk = readChunk(task.getStdoutFile(), stdoutOffset, limitBytes);
        String stderrChunk = readChunk(task.getStderrFile(), stderrOffset, limitBytes);
        long stdoutEnd = stdoutOffset + (stdoutChunk != null ? stdoutChunk.length() : 0);
        long stderrEnd = stderrOffset + (stderrChunk != null ? stderrChunk.length() : 0);

        return BackgroundTaskDetailResponse.builder()
                .taskId(task.getTaskId())
                .runId(task.getRunId())
                .status(task.getStatus() == null ? null : task.getStatus().name())
                .exitCode(task.getExitCode())
                .errorCode(task.getErrorCode())
                .errorMessage(task.getErrorMessage())
                .stdoutChunk(stdoutChunk)
                .stderrChunk(stderrChunk)
                .stdoutOffset(stdoutEnd)
                .stderrOffset(stderrEnd)
                .stdoutEof(stdoutEnd >= task.getStdoutBytes())
                .stderrEof(stderrEnd >= task.getStderrBytes())
                .stdoutBytes(task.getStdoutBytes())
                .stderrBytes(task.getStderrBytes())
                .command(task.getCommand())
                .cwd(task.getCwd())
                .launchMode(task.getLaunchMode() == null ? null : task.getLaunchMode().name())
                .timeoutMs(task.getTimeoutMs())
                .build();
    }

    public BackgroundTaskResponse cancelTask(String runId, String taskId) {
        BackgroundShellTask task = taskRepository.find(taskId).orElse(null);
        if (task == null || !runId.equals(task.getRunId())) {
            return null;
        }
        return BackgroundTaskResponse.builder()
                .taskId(task.getTaskId())
                .runId(task.getRunId())
                .status(task.getStatus() == null ? null : task.getStatus().name())
                .build();
    }

    private String readChunk(String filePath, long offset, int limitBytes) {
        if (filePath == null) {
            return null;
        }
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            int start = (int) Math.min(offset, bytes.length);
            int end = Math.min(start + limitBytes, bytes.length);
            if (start >= end) {
                return "";
            }
            return new String(bytes, start, end - start, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(读取错误: " + e.getMessage() + ")";
        }
    }
}
