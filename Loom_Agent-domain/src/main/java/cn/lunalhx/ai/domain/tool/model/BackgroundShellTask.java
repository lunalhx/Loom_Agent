package cn.lunalhx.ai.domain.tool.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class BackgroundShellTask {

    private String taskId;
    private String runId;
    private String conversationId;
    private String workspace;
    private String command;
    private String cwd;
    private BackgroundLaunchMode launchMode;
    private long timeoutMs;
    private Long pid;
    private BackgroundTaskStatus status;
    private Integer exitCode;
    private String errorCode;
    private String errorMessage;
    private String stdoutFile;
    private String stderrFile;
    private long stdoutBytes;
    private long stderrBytes;
    private Instant startedAt;
    private Instant completedAt;
    private boolean completionNotified;
    private Instant createdAt;
    private Instant updatedAt;

    public boolean isTerminal() {
        return status == BackgroundTaskStatus.SUCCEEDED
                || status == BackgroundTaskStatus.FAILED
                || status == BackgroundTaskStatus.TIMED_OUT
                || status == BackgroundTaskStatus.CANCELLED
                || status == BackgroundTaskStatus.LOST;
    }

    public boolean isRunning() {
        return status == BackgroundTaskStatus.STARTING || status == BackgroundTaskStatus.RUNNING;
    }

}
