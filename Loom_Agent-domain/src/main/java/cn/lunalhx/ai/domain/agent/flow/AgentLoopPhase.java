package cn.lunalhx.ai.domain.agent.flow;

/**
 * Typed loop control phases returned by the six core nodes and interpreted
 * by the main loop. Replaces the previous arbitrary string-based node jumps.
 */
public enum AgentLoopPhase {
    /** Continue to a specific node within the current round. */
    NEXT_NODE,
    /** Current round finished; start a new round at the prompt-build node. */
    NEXT_ROUND,
    /** Run completed with a final answer. */
    COMPLETE,
    /** Run paused waiting for user input. */
    PAUSE_USER_INPUT,
    /** Run terminated with an error. */
    FAIL
}