package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.agent.adapter.port.PersistentApprovalStore;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
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
        return find(approvalId).map(approval -> {
            approvals.remove(approvalId);
            return approval;
        });
    }

}
