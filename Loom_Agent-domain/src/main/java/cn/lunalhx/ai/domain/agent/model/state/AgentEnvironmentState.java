package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable environment state: workspace resolution, tool specs, and sub-agent capability.
 */
public final class AgentEnvironmentState {

    private Path resolvedWorkspace;
    private WorkspaceRef workspace;
    private String workspaceDisplayName;
    private List<ToolSpec> toolSpecs = new ArrayList<>();
    private boolean subAgentSpawnAllowed;

    public Path resolvedWorkspace() { return resolvedWorkspace; }
    public WorkspaceRef workspace() { return workspace; }
    public String workspaceDisplayName() { return workspaceDisplayName; }
    public List<ToolSpec> toolSpecs() { return toolSpecs; }
    public boolean subAgentSpawnAllowed() { return subAgentSpawnAllowed; }

    // -- package-private mutators for AgentContext delegation --

    public void setResolvedWorkspace(Path v) { this.resolvedWorkspace = v; }
    public void setWorkspace(WorkspaceRef v) { this.workspace = v; }
    public void setWorkspaceDisplayName(String v) { this.workspaceDisplayName = v; }
    public void setToolSpecs(List<ToolSpec> v) { this.toolSpecs = v; }
    public void setSubAgentSpawnAllowed(boolean v) { this.subAgentSpawnAllowed = v; }
}
