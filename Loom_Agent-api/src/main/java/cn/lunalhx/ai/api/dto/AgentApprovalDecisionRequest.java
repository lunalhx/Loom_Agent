package cn.lunalhx.ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentApprovalDecisionRequest {

    @NotBlank(message = "decision 不能为空")
    private String decision;

    @Size(max = 500, message = "reason 不能超过 500 个字符")
    private String reason;

    @Size(max = 64, message = "reasonCode 最长 64 个字符")
    private String reasonCode;

    @Size(max = 10, message = "allowedAlternatives 最多 10 个")
    private List<@Size(max = 200, message = "替代命令最长 200 个字符") String> allowedAlternatives;

    public AgentApprovalDecisionRequest(String decision, String reason) {
        this.decision = decision;
        this.reason = reason;
    }

}
