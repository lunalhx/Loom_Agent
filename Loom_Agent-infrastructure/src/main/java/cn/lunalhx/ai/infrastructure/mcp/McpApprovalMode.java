package cn.lunalhx.ai.infrastructure.mcp;

/**
 * Codex-style per-server approval mapping, folded into the shared
 * {@code ToolSpec.risky} flag of the loom tool chain.
 *
 * <ul>
 *   <li>{@link #AUTO}: risky = false — tools run without asking</li>
 *   <li>{@link #WRITES}: risky = true — only read-only tools skip approval</li>
 *   <li>{@link #PROMPT}: risky = true — always ask (same loom behaviour as WRITES)</li>
 *   <li>{@link #APPROVE}: risky = true — one-shot pre-approval not yet supported,
 *       mapped to the same loom behaviour as WRITES</li>
 * </ul>
 */
public enum McpApprovalMode {
    AUTO, WRITES, PROMPT, APPROVE
}
