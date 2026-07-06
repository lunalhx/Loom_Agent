package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.ApprovalDecisionResult;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryApprovalStore;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class InMemoryApprovalStoreDecisionTest {

    @Test
    public void concurrentDifferentDecisionsShouldAcceptExactlyOne() throws Exception {
        InMemoryApprovalStore store = new InMemoryApprovalStore();
        store.save(PendingApproval.builder()
                .approvalId("approval-1")
                .runId("run-1")
                .tool("write_file")
                .expiresAt(Instant.now().plusSeconds(60))
                .build());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ApprovalDecisionResult> approve = executor.submit(() -> {
                start.await();
                return store.decide("approval-1", ApprovalDecision.APPROVE, "yes");
            });
            Future<ApprovalDecisionResult> reject = executor.submit(() -> {
                start.await();
                return store.decide("approval-1", ApprovalDecision.REJECT, "no");
            });
            start.countDown();

            List<ApprovalDecisionResult.Outcome> outcomes =
                    List.of(approve.get().outcome(), reject.get().outcome());

            assertEquals(1, outcomes.stream()
                    .filter(value -> value == ApprovalDecisionResult.Outcome.ACCEPTED)
                    .count());
            assertEquals(1, outcomes.stream()
                    .filter(value -> value == ApprovalDecisionResult.Outcome.CONFLICT)
                    .count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void sameDecisionRetryShouldBeIdempotentAndResumableStateDurable() {
        InMemoryApprovalStore store = new InMemoryApprovalStore();
        store.save(PendingApproval.builder()
                .approvalId("approval-2")
                .runId("run-2")
                .tool("write_file")
                .expiresAt(Instant.now().plusSeconds(60))
                .build());

        assertEquals(ApprovalDecisionResult.Outcome.ACCEPTED,
                store.decide("approval-2", ApprovalDecision.APPROVE, "yes").outcome());
        assertEquals(ApprovalDecisionResult.Outcome.IDEMPOTENT,
                store.decide("approval-2", ApprovalDecision.APPROVE, "retry").outcome());
        store.markResumed("approval-2");

        assertTrue(store.find("approval-2").isPresent());
        assertEquals("RESUMED",
                store.find("approval-2").orElseThrow().getState().name());
    }
}
