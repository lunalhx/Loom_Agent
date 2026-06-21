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
public class AgentWorkspaceTreeRequest {

    @Size(max = 1024, message = "workspace 不能超过 1024 个字符")
    private String workspace;

    @Size(max = 1024, message = "path 不能超过 1024 个字符")
    private String path;

    @Min(value = 1, message = "limit 不能小于 1")
    @Max(value = 500, message = "limit 不能大于 500")
    private Integer limit;

}
