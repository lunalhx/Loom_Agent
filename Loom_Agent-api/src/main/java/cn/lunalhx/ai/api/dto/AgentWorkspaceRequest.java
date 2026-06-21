package cn.lunalhx.ai.api.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentWorkspaceRequest {

    @Size(max = 1024, message = "workspace 不能超过 1024 个字符")
    private String workspace;

}
