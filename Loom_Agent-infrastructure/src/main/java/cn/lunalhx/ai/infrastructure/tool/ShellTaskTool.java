package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
import cn.lunalhx.ai.domain.tool.model.BackgroundShellTask;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ShellTaskTool implements AgentTool {

    private static final long POLL_MAX_WAIT_MS = 30_000;
    private static final int DEFAULT_LIMIT_BYTES = 8192;

    private final BackgroundShellTaskRepository taskRepository;
    private final BackgroundProcessManager processManager;
    private final AgentRuntimeProperties properties;

    public ShellTaskTool(BackgroundShellTaskRepository taskRepository,
                         BackgroundProcessManager processManager,
                         AgentRuntimeProperties properties) {
        this.taskRepository = taskRepository;
        this.processManager = processManager;
        this.properties = properties;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("shell_task")
                .description("管理当前 run 的后台 shell 任务。list：列出所有任务及状态。poll：等待任务完成（最多30秒），读取 stdout/stderr 增量。cancel：终止指定任务。")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"list\",\"poll\",\"cancel\"],\"description\":\"操作类型\"},\"taskId\":{\"type\":\"string\",\"description\":\"任务 ID（poll 和 cancel 必需）\"},\"stdoutOffset\":{\"type\":\"integer\",\"default\":0,\"description\":\"stdout 读取偏移字节\"},\"stderrOffset\":{\"type\":\"integer\",\"default\":0,\"description\":\"stderr 读取偏移字节\"},\"limitBytes\":{\"type\":\"integer\",\"default\":8192,\"description\":\"读取字节上限\"}},\"required\":[\"action\"],\"additionalProperties\":false}")
                .build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            String action = call.getInput() != null
                    ? call.getInput().path("action").asText("list") : "list";
            return switch (action) {
                case "list" -> doList(call, startedAt);
                case "poll" -> doPoll(call, startedAt);
                case "cancel" -> doCancel(call, startedAt);
                default -> ToolResult.failure("unknown_action", "未知操作: " + action, startedAt);
            };
        } catch (Exception e) {
            return ToolResult.failure("shell_task_failed", e.getMessage(), startedAt);
        }
    }

    private ToolResult doList(ToolCall call, long startedAt) {
        List<BackgroundShellTask> tasks = taskRepository.findByRunId(call.getRunId());
        if (tasks.isEmpty()) {
            return ToolResult.success("当前 run 没有后台任务", false,
                    System.currentTimeMillis() - startedAt);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("后台任务列表 (").append(tasks.size()).append("):\n\n");
        for (BackgroundShellTask t : tasks) {
            sb.append("- task_id: ").append(t.getTaskId()).append("\n");
            sb.append("  command: ").append(t.getCommand()).append("\n");
            sb.append("  status: ").append(t.getStatus()).append("\n");
            sb.append("  launch_mode: ").append(t.getLaunchMode()).append("\n");
            if (t.getExitCode() != null) {
                sb.append("  exit_code: ").append(t.getExitCode()).append("\n");
            }
            if (t.getErrorCode() != null) {
                sb.append("  error_code: ").append(t.getErrorCode()).append("\n");
            }
            if (t.getErrorMessage() != null) {
                sb.append("  error_message: ").append(t.getErrorMessage()).append("\n");
            }
            sb.append("  stdout_bytes: ").append(t.getStdoutBytes()).append("\n");
            sb.append("  stderr_bytes: ").append(t.getStderrBytes()).append("\n");
            sb.append("\n");
        }
        return ToolResult.success(sb.toString().trim(), false,
                System.currentTimeMillis() - startedAt);
    }

    private ToolResult doPoll(ToolCall call, long startedAt) {
        String taskId = call.getInput() != null
                ? call.getInput().path("taskId").asText("") : "";
        if (taskId.isEmpty()) {
            return ToolResult.failure("missing_task_id", "poll 需要 taskId", startedAt);
        }

        BackgroundShellTask task = taskRepository.find(taskId).orElse(null);
        if (task == null || !call.getRunId().equals(task.getRunId())) {
            return ToolResult.failure("background_task_not_found", "任务未找到: " + taskId, startedAt);
        }

        // Wait for up to 30s if still running
        if (task.isRunning()) {
            long deadline = System.currentTimeMillis() + POLL_MAX_WAIT_MS;
            while (System.currentTimeMillis() < deadline && task.isRunning()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                task = taskRepository.find(taskId).orElse(task);
            }
        }

        long stdoutOffset = call.getInput() != null
                ? call.getInput().path("stdoutOffset").asLong(0) : 0;
        long stderrOffset = call.getInput() != null
                ? call.getInput().path("stderrOffset").asLong(0) : 0;
        int limitBytes = call.getInput() != null
                ? call.getInput().path("limitBytes").asInt(DEFAULT_LIMIT_BYTES) : DEFAULT_LIMIT_BYTES;

        String stdoutChunk = readFileChunk(task.getStdoutFile(), stdoutOffset, limitBytes);
        String stderrChunk = readFileChunk(task.getStderrFile(), stderrOffset, limitBytes);

        boolean stdoutEof = stdoutOffset + (stdoutChunk != null ? stdoutChunk.length() : 0) >= task.getStdoutBytes();
        boolean stderrEof = stderrOffset + (stderrChunk != null ? stderrChunk.length() : 0) >= task.getStderrBytes();

        StringBuilder sb = new StringBuilder();
        sb.append("task_id: ").append(task.getTaskId()).append("\n");
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
        sb.append("stdout_offset: ").append(stdoutOffset + (stdoutChunk != null ? stdoutChunk.length() : 0)).append("\n");
        sb.append("stderr_offset: ").append(stderrOffset + (stderrChunk != null ? stderrChunk.length() : 0)).append("\n");
        sb.append("stdout_eof: ").append(stdoutEof).append("\n");
        sb.append("stderr_eof: ").append(stderrEof).append("\n");
        sb.append("\n[stdout]:\n").append(stdoutChunk != null ? stdoutChunk : "(empty)");
        sb.append("\n[stderr]:\n").append(stderrChunk != null ? stderrChunk : "(empty)");

        return ToolResult.success(sb.toString(), false,
                System.currentTimeMillis() - startedAt);
    }

    private ToolResult doCancel(ToolCall call, long startedAt) {
        String taskId = call.getInput() != null
                ? call.getInput().path("taskId").asText("") : "";
        if (taskId.isEmpty()) {
            return ToolResult.failure("missing_task_id", "cancel 需要 taskId", startedAt);
        }

        BackgroundShellTask task = taskRepository.find(taskId).orElse(null);
        if (task == null || !call.getRunId().equals(task.getRunId())) {
            return ToolResult.failure("background_task_not_found", "任务未找到: " + taskId, startedAt);
        }

        if (task.isTerminal()) {
            return ToolResult.success("任务已处于终态: " + task.getStatus(), false,
                    System.currentTimeMillis() - startedAt);
        }

        boolean cancelled = processManager.cancel(call.getRunId(), taskId);
        if (cancelled) {
            task.setStatus(cn.lunalhx.ai.domain.tool.model.BackgroundTaskStatus.CANCELLED);
            task.setCompletedAt(java.time.Instant.now());
            taskRepository.save(task);
            return ToolResult.success("任务已取消: " + taskId, false,
                    System.currentTimeMillis() - startedAt);
        }
        return ToolResult.failure("cancel_failed", "无法取消任务，进程可能已结束", startedAt);
    }

    private String readFileChunk(String filePath, long offset, int limitBytes) {
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
        } catch (IOException e) {
            return "(读取错误: " + e.getMessage() + ")";
        }
    }

}
