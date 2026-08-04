package cn.lunalhx.ai.domain.agent.model.entity;

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

/**
 * A pending approval for a risky loom-code tool call. Risky tools are uniformly
 * shown as {@code RISKY}; content diff and stale-approval fingerprints are removed.
 */
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
    private String riskReason;
    private String operationPreview;
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
