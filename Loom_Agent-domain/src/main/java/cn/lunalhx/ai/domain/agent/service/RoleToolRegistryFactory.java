package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;

import java.util.List;
import java.util.Set;

public class RoleToolRegistryFactory {

    private static final String MCP_TOOL_NAME_PREFIX = "mcp__";
    private static final Set<String> READ_ONLY_TOOL_NAMES = Set.of(
            "list_dir", "read_file", "code_search", "run_shell", "git_op", "todo_write", ContextRecallTool.NAME, "find_files");

    private final List<AgentTool> tools;
    private final ToolSchemaValidator schemaValidator;

    public RoleToolRegistryFactory(List<AgentTool> tools, ToolSchemaValidator schemaValidator) {
        this.tools = tools;
        this.schemaValidator = schemaValidator;
    }

    public ToolRegistry create(AgentRole role) {
        AgentRole normalizedRole = role == null ? AgentRole.EXPLORER : role;
        List<AgentTool> selected = tools.stream()
                .filter(tool -> isAllowed(normalizedRole, tool.spec().getName()))
                .map(tool -> isReadOnlyRole(normalizedRole) ? new ReadOnlyAgentTool(tool) : tool)
                .toList();
        return new ToolRegistry(selected, schemaValidator);
    }

    private boolean isAllowed(AgentRole role, String toolName) {
        if (isReadOnlyRole(role)) {
            return READ_ONLY_TOOL_NAMES.contains(toolName) || toolName.startsWith(MCP_TOOL_NAME_PREFIX);
        }
        return !SubAgentToolSpecs.SPAWN_AGENTS.equals(toolName);
    }

    private boolean isReadOnlyRole(AgentRole role) {
        return role == AgentRole.EXPLORER || role == AgentRole.REVIEWER;
    }

    private static class ReadOnlyAgentTool implements AgentTool {

        private final AgentTool delegate;

        private ReadOnlyAgentTool(AgentTool delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolSpec spec() {
            return delegate.spec();
        }

        @Override
        public ToolPolicyDecision policy(ToolCall call) {
            ToolPolicyDecision decision = delegate.policy(call);
            if (decision == null || decision.getPermissionLevel() == null
                    || decision.getPermissionLevel() == ToolPermissionLevel.READ_ONLY) {
                return decision == null ? ToolPolicyDecision.readOnly("只读子 Agent 工具", spec().getName()) : decision;
            }
            return ToolPolicyDecision.highRiskDeny("只读子 Agent 不允许执行非只读工具动作", spec().getName());
        }

        @Override
        public ToolResult call(ToolCall call) {
            ToolPolicyDecision decision = policy(call);
            if (decision != null && decision.getPermissionLevel() != ToolPermissionLevel.READ_ONLY) {
                return ToolResult.failure("sub_agent_read_only_violation",
                        "只读子 Agent 不允许执行非只读工具动作：" + spec().getName(), 0L);
            }
            return delegate.call(call);
        }
    }

}
