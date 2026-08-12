package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunConfig;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable environment state: workspace resolution, tool specs, allowlist and approval policy.
 */
public final class AgentEnvironmentState {

    private Path resolvedWorkspace;
    private WorkspaceRef workspace;
    private String workspaceDisplayName;
    private List<ToolSpec> toolSpecs = new ArrayList<>();
    private List<String> allowedTools;
    private String approvalPolicy;
    private AgentRunConfig runConfig;
    private ExecutionProfile executionProfile;
    private PermissionPolicySnapshot permissionPolicySnapshot;

    public Path resolvedWorkspace() { return resolvedWorkspace; }
    public WorkspaceRef workspace() { return workspace; }
    public String workspaceDisplayName() { return workspaceDisplayName; }
    public List<ToolSpec> toolSpecs() { return toolSpecs; }
    public List<String> allowedTools() { return allowedTools; }
    public String approvalPolicy() { return approvalPolicy; }
    public AgentRunConfig runConfig() { return runConfig; }
    public ExecutionProfile executionProfile() { return executionProfile; }
    public PermissionPolicySnapshot permissionPolicySnapshot() { return permissionPolicySnapshot; }

    public void setResolvedWorkspace(Path v) { this.resolvedWorkspace = v; }
    public void setWorkspace(WorkspaceRef v) { this.workspace = v; }
    public void setWorkspaceDisplayName(String v) { this.workspaceDisplayName = v; }
    public void setToolSpecs(List<ToolSpec> v) { this.toolSpecs = v; }
    public void setAllowedTools(List<String> v) { this.allowedTools = v; }
    public void setApprovalPolicy(String v) { this.approvalPolicy = v; }
    public void setRunConfig(AgentRunConfig v) { this.runConfig = v; }
    public void setExecutionProfile(ExecutionProfile v) { this.executionProfile = v; }
    public void setPermissionPolicySnapshot(PermissionPolicySnapshot v) { this.permissionPolicySnapshot = v; }
}
