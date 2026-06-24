package cn.lunalhx.ai.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    private String workspace;

    @Min(value = 1, message = "maxSteps 不能小于 1")
    @Max(value = 30, message = "maxSteps 不能大于 30")
    private Integer maxSteps;

    private Boolean includeTrace;

}
