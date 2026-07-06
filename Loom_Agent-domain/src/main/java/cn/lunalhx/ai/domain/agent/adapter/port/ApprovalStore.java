package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.model.entity.ApprovalDecisionResult;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalRecordState;

import java.util.Optional;

public interface ApprovalStore {

    PendingApproval save(PendingApproval approval);

    Optional<PendingApproval> find(String approvalId);

    Optional<PendingApproval> consume(String approvalId);

    default ApprovalDecisionResult decide(
            String approvalId, ApprovalDecision decision, String reason) {
        Optional<PendingApproval> claimed = consume(approvalId);
        if (claimed.isEmpty()) {
            return ApprovalDecisionResult.of(
                    ApprovalDecisionResult.Outcome.NOT_FOUND, null);
        }
        PendingApproval approval = claimed.get();
        approval.setState(ApprovalRecordState.DECIDED);
        approval.setDecision(decision);
        approval.setDecisionReason(reason);
        return ApprovalDecisionResult.of(
                ApprovalDecisionResult.Outcome.ACCEPTED, approval);
    }

    default void markResumed(String approvalId) {
        // Legacy stores remove consumed approvals and need no extra transition.
    }

}
