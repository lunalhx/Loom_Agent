package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.Instant;

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
    private String riskReason;
    private String operationPreview;
    private String metadataJson;
    private String contextJson;
    private Instant createdAt;
    private Instant expiresAt;
    private Integer consumed;
    private String state;
    private String decision;
    private String decisionReason;
    private Instant createTime;
    private Instant updateTime;

}
