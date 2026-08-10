package cn.lunalhx.ai.domain.tool.model;

/** Base-session approval requirement, independent from effect authorization. */
public enum ApprovalRequirement {
    NONE,
    SESSION_POLICY;

    public boolean required() {
        return this == SESSION_POLICY;
    }
}
