package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentWorkspaceTreeResponse {

    private String workspace;
    private String displayName;
    private String path;
    private AgentWorkspaceTreeNode node;
    private Boolean truncated;

}
