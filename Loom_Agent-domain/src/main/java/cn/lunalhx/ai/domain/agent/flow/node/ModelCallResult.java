package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.BudgetCheckResult;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;

final class ModelCallResult {

    enum Kind {
        SUCCESS,
        BUDGET_BLOCKED,
        TRUNCATION_EXHAUSTED,
        ERROR
    }

    private final Kind kind;
    private final ModelChatResult chatResult;
    private final BudgetCheckResult budgetCheck;
    private final Throwable error;
    private final String attemptedModel;
    private final int requestedMaxTokens;

    private ModelCallResult(Kind kind, ModelChatResult chatResult, BudgetCheckResult budgetCheck,
                            Throwable error, String attemptedModel, int requestedMaxTokens) {
        this.kind = kind;
        this.chatResult = chatResult;
        this.budgetCheck = budgetCheck;
        this.error = error;
        this.attemptedModel = attemptedModel;
        this.requestedMaxTokens = requestedMaxTokens;
    }

    static ModelCallResult success(ModelChatResult result) {
        return new ModelCallResult(Kind.SUCCESS, result, null, null, null, 0);
    }

    static ModelCallResult budgetBlocked(BudgetCheckResult check) {
        return new ModelCallResult(Kind.BUDGET_BLOCKED, null, check, null, null, 0);
    }

    static ModelCallResult truncationExhausted(ModelChatResult result) {
        return new ModelCallResult(Kind.TRUNCATION_EXHAUSTED, result, null, null, null, 0);
    }

    static ModelCallResult error(Throwable error, String attemptedModel, int requestedMaxTokens) {
        return new ModelCallResult(Kind.ERROR, null, null, error, attemptedModel, requestedMaxTokens);
    }

    Kind kind() { return kind; }
    ModelChatResult chatResult() { return chatResult; }
    BudgetCheckResult budgetCheck() { return budgetCheck; }
    Throwable error() { return error; }
    String attemptedModel() { return attemptedModel; }
    int requestedMaxTokens() { return requestedMaxTokens; }

    boolean isSuccess() { return kind == Kind.SUCCESS; }
    boolean isBudgetBlocked() { return kind == Kind.BUDGET_BLOCKED; }
    boolean isTruncationExhausted() { return kind == Kind.TRUNCATION_EXHAUSTED; }
    boolean isError() { return kind == Kind.ERROR; }
}
