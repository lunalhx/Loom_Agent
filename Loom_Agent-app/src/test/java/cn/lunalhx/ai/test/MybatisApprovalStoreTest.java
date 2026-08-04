package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.ApprovalDecisionResult;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
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

            @Override
            public int markDecided(String approvalId, String decision, String decisionReason) {
                AgentPendingApprovalPO current = stored.get();
                if (current == null || !"PENDING".equals(current.getState())) {
                    return 0;
                }
                current.setState("DECIDED");
                current.setDecision(decision);
                current.setDecisionReason(decisionReason);
                return 1;
            }

            @Override
            public int markResumed(String approvalId) {
                AgentPendingApprovalPO current = stored.get();
                if (current == null || !"DECIDED".equals(current.getState())) {
                    return 0;
                }
                current.setState("RESUMED");
                return 1;
            }

            @Override
            public int deleteByConversationId(String conversationId) {
                return 0;
            }
        };
        MybatisApprovalStore store = new MybatisApprovalStore(dao, new ObjectMapper());
        PendingApproval approval = PendingApproval.builder()
                .approvalId("approval-1")
                .tool("delete_files")
                .metadata(Map.of("deletePreview", Map.of("fileCount", 2, "directoryCount", 1)))
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        store.save(approval);
        PendingApproval restored = store.find("approval-1").orElseThrow();

        assertEquals(2, ((Map<?, ?>) restored.getMetadata().get("deletePreview")).get("fileCount"));

        assertEquals(ApprovalDecisionResult.Outcome.ACCEPTED,
                store.decide("approval-1", ApprovalDecision.APPROVE, "ok").outcome());
        assertEquals(ApprovalDecisionResult.Outcome.IDEMPOTENT,
                store.decide("approval-1", ApprovalDecision.APPROVE, "retry").outcome());
        assertEquals(ApprovalDecisionResult.Outcome.CONFLICT,
                store.decide("approval-1", ApprovalDecision.REJECT, "no").outcome());
        store.markResumed("approval-1");
        assertEquals("RESUMED",
                store.find("approval-1").orElseThrow().getState().name());
    }
}
