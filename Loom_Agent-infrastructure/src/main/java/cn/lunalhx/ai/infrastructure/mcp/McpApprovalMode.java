package cn.lunalhx.ai.infrastructure.mcp;

/**
 * Codex-style per-server base-session approval mapping.
 *
 * <ul>
 *   <li>{@link #AUTO}: no session approval is required</li>
 *   <li>{@link #WRITES}: session policy approval is required</li>
 *   <li>{@link #PROMPT}: session policy approval is required</li>
 *   <li>{@link #APPROVE}: one-shot pre-approval is not yet supported,
 *       mapped to the same loom behaviour as WRITES</li>
 * </ul>
 */
public enum McpApprovalMode {
    AUTO, WRITES, PROMPT, APPROVE
}
