package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentApprovalResponse {

    private String approvalId;
    private String status;
    private String requestId;
    private String conversationId;
    private String tool;
    private Map<String, Object> input;
    private String permissionLevel;
    private String riskReason;
    private String operationPreview;
    private String expiresAt;

}
