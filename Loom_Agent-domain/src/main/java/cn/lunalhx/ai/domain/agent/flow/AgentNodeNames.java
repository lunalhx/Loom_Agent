package cn.lunalhx.ai.domain.agent.flow;

import java.util.List;

public final class AgentNodeNames {

    public static final String PROMPT_BUILD = "prompt_build";
    public static final String MODEL_CALL = "model_call";
    public static final String DECISION = "decision";
    public static final String TOOL_DISPATCH = "tool_dispatch";
    public static final String OBSERVATION = "observation";

    private AgentNodeNames() {
    }

    public static final List<String> ALL = List.of(
            PROMPT_BUILD, MODEL_CALL, DECISION, TOOL_DISPATCH, OBSERVATION);
}
