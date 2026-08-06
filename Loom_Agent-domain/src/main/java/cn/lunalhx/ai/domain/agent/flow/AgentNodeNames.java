package cn.lunalhx.ai.domain.agent.flow;

import java.util.List;

public final class AgentNodeNames {

    public static final String PROMPT_BUILD = "prompt_build";
    public static final String MODEL_CALL = "model_call";
    public static final String DECISION = "decision";
    /** Tool input governance. Persistent name stays {@code tool_dispatch} for
     *  checkpoint/trace consumer compatibility; display as {@code tool_input}. */
    public static final String TOOL_INPUT = "tool_dispatch";
    /** Actual tool execution (registry call) — new node. */
    public static final String TOOL_EXECUTE = "tool_execute";
    /** Tool output normalization/redaction. Persistent name stays
     *  {@code observation} for checkpoint/trace consumer compatibility;
     *  display as {@code tool_output}. */
    public static final String TOOL_OUTPUT = "observation";

    /** Deprecated alias — use {@link #TOOL_INPUT}. */
    @Deprecated
    public static final String TOOL_DISPATCH = TOOL_INPUT;
    /** Deprecated alias — use {@link #TOOL_OUTPUT}. */
    @Deprecated
    public static final String OBSERVATION = TOOL_OUTPUT;

    private AgentNodeNames() {
    }

    public static final List<String> ALL = List.of(
            PROMPT_BUILD, MODEL_CALL, DECISION, TOOL_INPUT, TOOL_EXECUTE, TOOL_OUTPUT);
}
