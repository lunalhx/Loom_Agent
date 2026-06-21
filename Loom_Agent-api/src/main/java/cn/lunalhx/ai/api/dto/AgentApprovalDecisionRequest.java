package cn.lunalhx.ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentApprovalDecisionRequest {

    @NotBlank(message = "decision 不能为空")
    private String decision;

    @Size(max = 500, message = "reason 不能超过 500 个字符")
    private String reason;

}
