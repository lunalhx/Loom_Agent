package cn.lunalhx.ai.domain.tool.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolPolicyDecision {

    private ToolPermissionLevel permissionLevel;
    private String riskReason;
    private String operationPreview;
    private ApprovalDiff diff;
    private String policyFingerprint;
    private Map<String, Object> metadata;
    private String validationErrorCode;
    private String validationMessage;

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

    public static ToolPolicyDecision validationFailure(String errorCode, String message, String preview) {
        ToolPolicyDecision decision = new ToolPolicyDecision();
        decision.setValidationErrorCode(errorCode);
        decision.setValidationMessage(message);
        decision.setOperationPreview(preview);
        return decision;
    }

    public boolean hasValidationFailure() {
        return validationErrorCode != null;
    }

    private static ToolPolicyDecision of(ToolPermissionLevel level, String reason, String preview) {
        return ToolPolicyDecision.builder()
                .permissionLevel(level)
                .riskReason(reason)
                .operationPreview(preview)
                .build();
    }

}
