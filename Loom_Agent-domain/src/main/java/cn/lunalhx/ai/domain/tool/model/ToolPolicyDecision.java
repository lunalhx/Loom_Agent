package cn.lunalhx.ai.domain.tool.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Simplified policy decision for a tool call. Risky tools are uniformly
 * {@code RISKY}; read-only tools are {@code READ_ONLY}. Content diff and
 * stale-approval fingerprints are removed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolPolicyDecision {

    public enum Permission { READ_ONLY, RISKY }

    private Permission permission;
    private String riskReason;
    private String operationPreview;
    private Map<String, Object> metadata;

    public static ToolPolicyDecision readOnly(String reason, String preview) {
        return ToolPolicyDecision.builder()
                .permission(Permission.READ_ONLY)
                .riskReason(reason)
                .operationPreview(preview)
                .build();
    }

    public static ToolPolicyDecision risky(String reason, String preview) {
        return ToolPolicyDecision.builder()
                .permission(Permission.RISKY)
                .riskReason(reason)
                .operationPreview(preview)
                .build();
    }
}
