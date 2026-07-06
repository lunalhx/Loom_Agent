package cn.lunalhx.ai.domain.agent.model.entity;

public record ApprovalDecisionResult(Outcome outcome, PendingApproval approval) {

    public enum Outcome {
        ACCEPTED,
        IDEMPOTENT,
        CONFLICT,
        NOT_FOUND
    }

    public static ApprovalDecisionResult of(Outcome outcome, PendingApproval approval) {
        return new ApprovalDecisionResult(outcome, approval);
    }
}
