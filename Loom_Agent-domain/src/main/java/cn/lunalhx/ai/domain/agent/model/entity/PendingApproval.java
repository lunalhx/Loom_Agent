package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingApproval {

    private String approvalId;
    private String requestId;
    private String conversationId;
    private String tool;
    private Map<String, Object> input;
    private ToolPermissionLevel permissionLevel;
    private String riskReason;
    private String operationPreview;
    private Instant createdAt;
    private Instant expiresAt;
    private AgentContext context;

    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

}
