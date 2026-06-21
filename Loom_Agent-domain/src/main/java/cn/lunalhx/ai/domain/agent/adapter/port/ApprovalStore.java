package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;

import java.util.Optional;

public interface ApprovalStore {

    PendingApproval save(PendingApproval approval);

    Optional<PendingApproval> find(String approvalId);

    Optional<PendingApproval> consume(String approvalId);

}
