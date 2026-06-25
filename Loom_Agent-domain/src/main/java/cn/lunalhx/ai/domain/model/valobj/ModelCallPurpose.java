package cn.lunalhx.ai.domain.model.valobj;

public enum ModelCallPurpose {

    CONTROL_JSON(false, false),
    FINAL_TEXT(true, false),
    TOOL_RESULT_SUMMARY(false, true),
    CONTEXT_SUMMARY(false, true);

    private final boolean continuationAllowed;
    private final boolean inputCompactionPreferred;

    ModelCallPurpose(boolean continuationAllowed, boolean inputCompactionPreferred) {
        this.continuationAllowed = continuationAllowed;
        this.inputCompactionPreferred = inputCompactionPreferred;
    }

    public boolean isContinuationAllowed() {
        return continuationAllowed;
    }

    public boolean isInputCompactionPreferred() {
        return inputCompactionPreferred;
    }

}
