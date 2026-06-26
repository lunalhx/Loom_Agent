package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentPendingApprovalPO {

    private Long id;
    private String approvalId;
    private String runId;
    private String requestId;
    private String conversationId;
    private String resolvedWorkspace;
    private String workspaceDisplayName;
    private String tool;
    private String inputJson;
    private String permissionLevel;
    private String riskReason;
    private String operationPreview;
    private String diffJson;
    private String contextJson;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private Integer consumed;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
