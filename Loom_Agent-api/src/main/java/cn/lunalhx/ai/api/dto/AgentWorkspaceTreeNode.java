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
public class AgentWorkspaceTreeNode {

    private String name;
    private String path;
    private String type;
    private Boolean hasChildren;
    private Long size;
    private Long lastModified;
    private List<AgentWorkspaceTreeNode> children;

}
