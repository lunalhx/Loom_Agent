package cn.lunalhx.ai.domain.agent.model.valobj;

/**
 * Explicit user choice when leaving an active recoverable Run. The first
 * interrupt only requests this choice; it does not imply either action.
 */
public enum RunExitAction {
    SUSPEND,
    ABANDON
}
