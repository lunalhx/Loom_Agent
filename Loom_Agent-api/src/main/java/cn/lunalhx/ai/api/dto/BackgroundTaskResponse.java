package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackgroundTaskResponse {

    private String taskId;
    private String runId;
    private String conversationId;
    private String workspace;
    private String command;
    private String cwd;
    private String launchMode;
    private long timeoutMs;
    private Long pid;
    private String status;
    private Integer exitCode;
    private String errorCode;
    private String errorMessage;
    private long stdoutBytes;
    private long stderrBytes;
    private String startedAt;
    private String completedAt;
    private boolean completionNotified;

}
