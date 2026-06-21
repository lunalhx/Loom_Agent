package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentWorkspaceResponse {

    private String workspace;
    private String displayName;
    private String defaultWorkspace;
    private List<String> allowedRoots;
    private Boolean sandboxRoot;
    private String message;

}
