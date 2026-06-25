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
public class AgentUserInputRequest {

    @NotBlank(message = "action 不能为空")
    private String action;

    @Size(max = 4000, message = "message 不能超过 4000 个字符")
    private String message;

}
