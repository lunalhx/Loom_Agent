package cn.lunalhx.ai.domain.agent.model.valobj;

public enum AgentStopReason {

    FINAL_ANSWER,
    MAX_STEPS,
    PARSE_ERROR,
    TOOL_ERROR,
    TIMEOUT,
    MODEL_ERROR,
    BUDGET_EXCEEDED,
    CONTEXT_OVERFLOW,
    NO_PROGRESS

}
