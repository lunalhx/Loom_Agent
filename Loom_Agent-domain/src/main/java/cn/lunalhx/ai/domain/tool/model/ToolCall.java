package cn.lunalhx.ai.domain.tool.model;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.entity.DelegateRequest;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
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
    private String toolCallId;
    private JsonNode input;
    private WorkspaceRef workspace;
    private Path workspaceRoot;
    private String runId;
    private String rootRunId;
    private String conversationId;
    private AgentRuntimeProperties runtimeProperties;
    private java.util.Set<String> secretEnvNames;
    private String recentSummary;
    private CollaborationMode collaborationMode;
    private EffectProfile effectProfile;
    private ExecutionProfile executionProfile;
    private DelegateRequest delegateRequest;

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
