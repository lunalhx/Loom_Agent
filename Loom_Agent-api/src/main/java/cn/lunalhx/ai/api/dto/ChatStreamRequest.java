package cn.lunalhx.ai.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamRequest implements Serializable {

    private static final long serialVersionUID = 5302195587532289964L;

    @NotBlank(message = "message 不能为空")
    @Size(max = 20000, message = "message 最长 20000 个字符")
    private String message;

    @Size(max = 64, message = "conversationId 最长 64 个字符")
    @Pattern(regexp = "^[a-zA-Z0-9_-]*$", message = "conversationId 只能包含字母、数字、下划线和中划线")
    private String conversationId;

    @Size(max = 4000, message = "systemPrompt 最长 4000 个字符")
    private String systemPrompt;

    @Pattern(regexp = "deepseek-v4-flash|deepseek-v4-pro", message = "model 只支持 deepseek-v4-flash 或 deepseek-v4-pro")
    private String model;

    @DecimalMin(value = "0.0", message = "temperature 不能小于 0")
    @DecimalMax(value = "2.0", message = "temperature 不能大于 2")
    private Double temperature;

    @Min(value = 1, message = "maxTokens 不能小于 1")
    @Max(value = 384000, message = "maxTokens 不能大于 384000")
    private Integer maxTokens;

    @Builder.Default
    private ResponseFormat responseFormat = ResponseFormat.TEXT;

}
