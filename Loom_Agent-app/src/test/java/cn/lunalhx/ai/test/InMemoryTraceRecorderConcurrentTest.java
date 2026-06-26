package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.flow.node.StartNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class InMemoryTraceRecorderConcurrentTest {

    @Test
    public void shouldHandleConcurrentWritesToSameRunWithoutExceptionOrLoss() throws InterruptedException {
        InMemoryTraceRecorder traceRecorder = new InMemoryTraceRecorder();
        String runId = "concurrent-run";
        int threadCount = 8;
        int eventsPerThread = 100;
        int totalEvents = threadCount * eventsPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Throwable> failures = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < eventsPerThread; i++) {
                        AgentContext context = new AgentContext();
                        context.setRunId(runId);
                        context.setRootRunId(runId);
                        context.setTraceId("trace-" + runId);
                        context.setRequestId("req-" + threadIndex + "-" + i);
                        context.setConversationId("conv-" + runId);
                        context.setQuestion("test");
                        context.setMaxSteps(3);

                        traceRecorder.recordNodeStart(context, new StartNode(), null);
                    }
                } catch (Throwable e) {
                    synchronized (failures) {
                        failures.add(e);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertTrue("Concurrent writes threw exceptions: " + failures, failures.isEmpty());

        List<AgentTraceEvent> timeline = traceRecorder.timeline(runId);
        assertEquals(totalEvents, timeline.size());

        for (AgentTraceEvent event : timeline) {
            assertEquals(runId, event.getRunId());
        }
    }
}
