package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisApprovalStore;
import cn.lunalhx.ai.infrastructure.dao.AgentPendingApprovalDao;
import cn.lunalhx.ai.infrastructure.dao.po.AgentPendingApprovalPO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;

public class MybatisApprovalStoreTest {

    @Test
    public void shouldRoundTripPolicyFingerprintAndMetadata() {
        AtomicReference<AgentPendingApprovalPO> stored = new AtomicReference<>();
        AgentPendingApprovalDao dao = new AgentPendingApprovalDao() {
            @Override
            public int upsert(AgentPendingApprovalPO approval) {
                stored.set(approval);
                return 1;
            }

            @Override
            public AgentPendingApprovalPO selectByApprovalId(String approvalId) {
                return stored.get();
            }

            @Override
            public int markConsumed(String approvalId) {
                return 1;
            }
        };
        MybatisApprovalStore store = new MybatisApprovalStore(dao, new ObjectMapper());
        PendingApproval approval = PendingApproval.builder()
                .approvalId("approval-1")
                .tool("delete_files")
                .permissionLevel(ToolPermissionLevel.HIGH_RISK_CONFIRM)
                .policyFingerprint("manifest-sha256")
                .metadata(Map.of("deletePreview", Map.of("fileCount", 2, "directoryCount", 1)))
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        store.save(approval);
        PendingApproval restored = store.find("approval-1").orElseThrow();

        assertEquals("manifest-sha256", restored.getPolicyFingerprint());
        assertEquals(2, ((Map<?, ?>) restored.getMetadata().get("deletePreview")).get("fileCount"));
        assertEquals("manifest-sha256", stored.get().getPolicyFingerprint());
    }
}
