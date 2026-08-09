package cn.lunalhx.ai.infrastructure.mcp;

import java.util.List;

/**
 * loom-specific metadata for one MCP server, aligned by connection name with
 * {@code spring.ai.mcp.client.stdio.connections.<name>}.
 *
 * @param approvalMode Codex-style approval mapping for every tool of this server
 * @param enabledTools tool allowlist (empty = all tools)
 * @param disabledTools tool denylist (applied after the allowlist)
 */
public record McpServerConfig(McpApprovalMode approvalMode,
                              List<String> enabledTools,
                              List<String> disabledTools) {

    public McpServerConfig {
        approvalMode = approvalMode == null ? McpApprovalMode.WRITES : approvalMode;
        enabledTools = enabledTools == null ? List.of() : List.copyOf(enabledTools);
        disabledTools = disabledTools == null ? List.of() : List.copyOf(disabledTools);
    }

    public static McpServerConfig defaults() {
        return new McpServerConfig(McpApprovalMode.WRITES, List.of(), List.of());
    }
}
