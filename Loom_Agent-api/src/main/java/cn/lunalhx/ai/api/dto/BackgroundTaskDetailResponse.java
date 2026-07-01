package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackgroundTaskDetailResponse {

    private String taskId;
    private String runId;
    private String status;
    private Integer exitCode;
    private String errorCode;
    private String errorMessage;
    private String stdoutChunk;
    private String stderrChunk;
    private long stdoutOffset;
    private long stderrOffset;
    private boolean stdoutEof;
    private boolean stderrEof;
    private long stdoutBytes;
    private long stderrBytes;
    private String command;
    private String cwd;
    private String launchMode;
    private long timeoutMs;

}
