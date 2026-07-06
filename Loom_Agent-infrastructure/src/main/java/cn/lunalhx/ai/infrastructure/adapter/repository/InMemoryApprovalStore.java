package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.agent.adapter.port.PersistentApprovalStore;
import cn.lunalhx.ai.domain.agent.model.entity.ApprovalDecisionResult;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalRecordState;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryStoreProperties;
import com.google.common.cache.CacheBuilder;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public class InMemoryApprovalStore implements PersistentApprovalStore {

    private final ConcurrentMap<String, PendingApproval> approvals;

    public InMemoryApprovalStore() {
        this.approvals = new ConcurrentHashMap<>();
    }

    public InMemoryApprovalStore(MemoryStoreProperties props) {
        this.approvals = CacheBuilder.newBuilder()
                .maximumSize(props.getMaxApprovals())
                .expireAfterAccess(props.getTtlSeconds(), TimeUnit.SECONDS)
                .<String, PendingApproval>build()
                .asMap();
    }

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
        if (StringUtils.isBlank(approvalId)) {
            return Optional.empty();
        }
        while (true) {
            PendingApproval approval = approvals.get(approvalId);
            if (approval == null) {
                return Optional.empty();
            }
            if (approval.expired(Instant.now())) {
                approvals.remove(approvalId, approval);
                return Optional.empty();
            }
            if (approvals.remove(approvalId, approval)) {
                return Optional.of(approval);
            }
        }
    }

    @Override
    public ApprovalDecisionResult decide(
            String approvalId, ApprovalDecision decision, String reason) {
        PendingApproval approval = find(approvalId).orElse(null);
        if (approval == null) {
            return ApprovalDecisionResult.of(
                    ApprovalDecisionResult.Outcome.NOT_FOUND, null);
        }
        synchronized (approval) {
            if (approval.getState() == ApprovalRecordState.PENDING) {
                approval.setState(ApprovalRecordState.DECIDED);
                approval.setDecision(decision);
                approval.setDecisionReason(reason);
                return ApprovalDecisionResult.of(
                        ApprovalDecisionResult.Outcome.ACCEPTED, approval);
            }
            if (approval.getDecision() == decision) {
                return ApprovalDecisionResult.of(
                        ApprovalDecisionResult.Outcome.IDEMPOTENT, approval);
            }
            return ApprovalDecisionResult.of(
                    ApprovalDecisionResult.Outcome.CONFLICT, approval);
        }
    }

    @Override
    public void markResumed(String approvalId) {
        PendingApproval approval = approvals.get(approvalId);
        if (approval != null) {
            synchronized (approval) {
                if (approval.getState() == ApprovalRecordState.DECIDED) {
                    approval.setState(ApprovalRecordState.RESUMED);
                }
            }
        }
    }

}
