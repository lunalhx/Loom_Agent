package cn.lunalhx.ai.domain.agent.flow;

public final class AgentNodeNames {

    public static final String START = "start";
    public static final String PLANNER = "planner";
    public static final String RENDER_PROMPT = "render_prompt";
    public static final String MODEL_CALL = "model_call";
    public static final String DECISION = "decision";
    public static final String APPROVAL_GATE = "approval_gate";
    public static final String TOOL_DISPATCH = "tool_dispatch";
    public static final String SUB_AGENT_DISPATCH = "sub_agent_dispatch";
    public static final String OBSERVATION = "observation";
    public static final String REPLAN_GUARD = "replan_guard";
    public static final String REPLAN = "replan";
    public static final String FINAL_ANSWER = "final_answer";
    public static final String FAIL = "fail";

    private AgentNodeNames() {
    }

}
