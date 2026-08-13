package cn.lunalhx.ai.domain.agent.model.valobj;

public enum ContextOverflowStage {

    NONE,
    REACTIVE_COMPACTED,
    FALLBACK_MODEL_SELECTED,
    DEEP_SUMMARY_APPLIED,
    WAITING_USER_INPUT

}
