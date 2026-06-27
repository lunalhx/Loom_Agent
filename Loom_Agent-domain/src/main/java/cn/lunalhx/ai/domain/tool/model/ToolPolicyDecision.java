package cn.lunalhx.ai.domain.tool.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolPolicyDecision {

    private ToolPermissionLevel permissionLevel;
    private String riskReason;
    private String operationPreview;
    private ApprovalDiff diff;

    public static ToolPolicyDecision readOnly(String reason, String preview) {
        return of(ToolPermissionLevel.READ_ONLY, reason, preview);
    }

    public static ToolPolicyDecision writeConfirm(String reason, String preview) {
        return of(ToolPermissionLevel.WRITE_CONFIRM, reason, preview);
    }

    public static ToolPolicyDecision highRiskConfirm(String reason, String preview) {
        return of(ToolPermissionLevel.HIGH_RISK_CONFIRM, reason, preview);
    }

    public static ToolPolicyDecision highRiskDeny(String reason, String preview) {
        return of(ToolPermissionLevel.HIGH_RISK_DENY, reason, preview);
    }

    private static ToolPolicyDecision of(ToolPermissionLevel level, String reason, String preview) {
        return ToolPolicyDecision.builder()
                .permissionLevel(level)
                .riskReason(reason)
                .operationPreview(preview)
                .build();
    }

}
