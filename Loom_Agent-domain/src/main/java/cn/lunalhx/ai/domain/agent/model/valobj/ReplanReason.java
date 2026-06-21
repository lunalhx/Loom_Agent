package cn.lunalhx.ai.domain.agent.model.valobj;

public enum ReplanReason {

    TOOL_FAILURE,
    APPROVAL_REJECTED,
    POLICY_DENIED,
    REPEATED_ERROR,
    INCOMPLETE_PLAN,
    UNSAFE_RESUME,
    MANUAL_RESUME

}
