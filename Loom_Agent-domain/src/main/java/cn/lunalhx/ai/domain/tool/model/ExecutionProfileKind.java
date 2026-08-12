package cn.lunalhx.ai.domain.tool.model;

/** Native capability profile selected before a tool call is authorized. */
public enum ExecutionProfileKind {
    PLAN_SANDBOX,
    BUILD_SANDBOX,
    DELEGATE_SANDBOX,
    DANGER_FULL_ACCESS
}
