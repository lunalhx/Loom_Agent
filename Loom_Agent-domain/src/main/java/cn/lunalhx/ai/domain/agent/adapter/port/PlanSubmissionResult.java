package cn.lunalhx.ai.domain.agent.adapter.port;

/** Outcome returned after runtime validation of a Plan Submission. */
public record PlanSubmissionResult(
        Outcome outcome,
        String planId,
        int revision,
        String message
) {

    public enum Outcome {
        PREPARED,
        SUBMITTED,
        CONFLICT,
        REJECTED
    }

    public static PlanSubmissionResult prepared(String planId, int revision) {
        return new PlanSubmissionResult(Outcome.PREPARED, planId, revision,
                "Plan submission prepared: " + planId + " revision " + revision);
    }

    public static PlanSubmissionResult submitted(String planId, int revision) {
        return new PlanSubmissionResult(Outcome.SUBMITTED, planId, revision,
                "Plan submitted: " + planId + " revision " + revision);
    }

    public static PlanSubmissionResult conflict(String message) {
        return new PlanSubmissionResult(Outcome.CONFLICT, null, 0,
                message == null || message.isBlank() ? "Plan Conflict" : message);
    }

    public static PlanSubmissionResult rejected(String message) {
        return new PlanSubmissionResult(Outcome.REJECTED, null, 0,
                message == null || message.isBlank() ? "Plan Submission rejected" : message);
    }
}
