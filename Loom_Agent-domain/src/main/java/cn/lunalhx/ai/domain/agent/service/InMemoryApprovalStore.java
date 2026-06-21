package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.PersistentApprovalStore;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryApprovalStore implements PersistentApprovalStore {

    private final ConcurrentMap<String, PendingApproval> approvals = new ConcurrentHashMap<>();

    @Override
    public PendingApproval save(PendingApproval approval) {
        approvals.put(approval.getApprovalId(), approval);
        return approval;
    }

    @Override
    public Optional<PendingApproval> find(String approvalId) {
        if (StringUtils.isBlank(approvalId)) {
            return Optional.empty();
        }
        PendingApproval approval = approvals.get(approvalId);
        if (approval == null) {
            return Optional.empty();
        }
        if (approval.expired(Instant.now())) {
            approvals.remove(approvalId);
            return Optional.empty();
        }
        return Optional.of(approval);
    }

    @Override
    public Optional<PendingApproval> consume(String approvalId) {
        return find(approvalId).map(approval -> {
            approvals.remove(approvalId);
            return approval;
        });
    }

}
