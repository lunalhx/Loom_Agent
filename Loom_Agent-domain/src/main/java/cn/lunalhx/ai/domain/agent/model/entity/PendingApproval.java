package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.tool.model.ApprovalDiff;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalRecordState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingApproval {

    private String approvalId;
    private String runId;
    private String requestId;
    private String conversationId;
    private Path resolvedWorkspace;
    private WorkspaceRef workspace;
    private String workspaceDisplayName;
    private String tool;
    private Map<String, Object> input;
    private ToolPermissionLevel permissionLevel;
    private String riskReason;
    private String operationPreview;
    private ApprovalDiff diff;
    private String policyFingerprint;
    private Map<String, Object> metadata;
    private Instant createdAt;
    private Instant expiresAt;
    private AgentContext context;
    @Builder.Default
    private ApprovalRecordState state = ApprovalRecordState.PENDING;
    private ApprovalDecision decision;
    private String decisionReason;

    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

}
