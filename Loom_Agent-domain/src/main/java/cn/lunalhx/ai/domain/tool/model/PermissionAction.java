package cn.lunalhx.ai.domain.tool.model;

/** The only permission outcomes. Their order is intentionally not a risk score. */
public enum PermissionAction {
    ALLOW,
    ASK,
    DENY
}
