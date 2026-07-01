package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

@Data
public class BackgroundShellTaskPO {

    private Long id;
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
    private String stdoutFile;
    private String stderrFile;
    private long stdoutBytes;
    private long stderrBytes;
    private String startedAt;
    private String completedAt;
    private int completionNotified;
    private String createTime;
    private String updateTime;

}
