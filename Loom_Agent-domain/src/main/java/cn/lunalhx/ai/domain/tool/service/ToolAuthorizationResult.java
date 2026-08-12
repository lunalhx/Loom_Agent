package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.model.ToolResult;

/** Exactly one of authorizedCall or rejection is present. */
public record ToolAuthorizationResult(AuthorizedToolCall authorizedCall, ToolResult rejection) {
    public static ToolAuthorizationResult authorized(AuthorizedToolCall call) {
        return new ToolAuthorizationResult(call, null);
    }
    public static ToolAuthorizationResult rejected(ToolResult result) {
        return new ToolAuthorizationResult(null, result);
    }
    public boolean authorized() { return authorizedCall != null; }
}
