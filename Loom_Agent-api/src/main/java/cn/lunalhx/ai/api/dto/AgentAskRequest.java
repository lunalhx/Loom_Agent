package cn.lunalhx.ai.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentAskRequest {

    @Size(max = 4000, message = "question 不能超过 4000 个字符")
    private String question;

    @Size(max = 4000, message = "message 不能超过 4000 个字符")
    private String message;

    @Size(max = 64, message = "conversationId 最长 64 个字符")
    @Pattern(regexp = "^[a-zA-Z0-9_-]*$", message = "conversationId 只能包含字母、数字、下划线和中划线")
    private String conversationId;

    private String workspace;

    @Min(value = 1, message = "maxSteps 不能小于 1")
    @Max(value = 30, message = "maxSteps 不能大于 30")
    private Integer maxSteps;

    private Boolean includeTrace;

}
