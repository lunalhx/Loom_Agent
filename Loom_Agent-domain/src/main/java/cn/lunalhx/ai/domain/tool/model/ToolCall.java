package cn.lunalhx.ai.domain.tool.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {

    private String name;
    private JsonNode input;
    private WorkspaceRef workspace;
    private Path workspaceRoot;
    private String runId;
    private String rootRunId;
    private String conversationId;
    private String approvedPolicyFingerprint;
    private List<String> activeSkillNames;

    public WorkspaceRef workspaceRef() {
        if (workspace != null) {
            return workspace;
        }
        if (workspaceRoot != null) {
            return WorkspaceRef.local(workspaceRoot, workspaceRoot.getFileName() == null ? workspaceRoot.toString() : workspaceRoot.getFileName().toString());
        }
        return null;
    }

}
