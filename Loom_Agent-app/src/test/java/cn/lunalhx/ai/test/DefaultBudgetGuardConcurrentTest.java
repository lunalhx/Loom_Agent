package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.BudgetCheckResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.DefaultBudgetGuard;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DefaultBudgetGuardConcurrentTest {

    @Test
    public void shouldNotLoseUpdatesUnderConcurrentRecordings() throws InterruptedException {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getBudget().setEnabled(true);
        DefaultBudgetGuard guard = new DefaultBudgetGuard(properties);
        String rootRunId = "concurrent-budget";
        int threadCount = 8;
        int callsPerThread = 100;
        int tokensPerCall = 10;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Throwable> failures = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < callsPerThread; i++) {
                        AgentContext context = new AgentContext();
                        context.setRunId(rootRunId);
                        context.setRootRunId(rootRunId);
                        guard.recordModelUsage(context, TokenUsage.builder()
                                .promptTokens(tokensPerCall)
                                .completionTokens(tokensPerCall)
                                .totalTokens(tokensPerCall * 2)
                                .build());
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

        AgentContext probe = new AgentContext();
        probe.setRunId(rootRunId);
        probe.setRootRunId(rootRunId);
        long totalExpected = (long) threadCount * callsPerThread * tokensPerCall * 2;
        BudgetCheckResult result = guard.checkBeforeModelCall(probe, "node", "test");
        assertEquals(totalExpected, result.getUsedTokens());
        assertEquals((long) threadCount * callsPerThread * tokensPerCall, probe.getUsedPromptTokens());
        assertEquals((long) threadCount * callsPerThread * tokensPerCall, probe.getUsedCompletionTokens());
    }

    @Test
    public void shouldShareBudgetAcrossParentAndChildContexts() throws InterruptedException {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getBudget().setEnabled(true);
        DefaultBudgetGuard guard = new DefaultBudgetGuard(properties);
        String rootRunId = "shared-budget";
        int threads = 4;
        int callsPerThread = 50;
        int tokensPerCall = 10;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadIndex = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < callsPerThread; i++) {
                        AgentContext context = new AgentContext();
                        context.setRunId("child-" + threadIndex);
                        context.setRootRunId(rootRunId);
                        guard.recordModelUsage(context, TokenUsage.builder()
                                .promptTokens(tokensPerCall)
                                .completionTokens(tokensPerCall)
                                .totalTokens(tokensPerCall * 2)
                                .build());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        AgentContext rootContext = new AgentContext();
        rootContext.setRunId(rootRunId);
        rootContext.setRootRunId(rootRunId);
        long totalExpected = (long) threads * callsPerThread * tokensPerCall * 2;
        BudgetCheckResult result = guard.checkBeforeModelCall(rootContext, "node", "test");
        assertEquals(totalExpected, result.getUsedTokens());
    }

    @Test
    public void shouldBlockAfterConcurrentUsageExceedsBudget() throws InterruptedException {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getBudget().setEnabled(true);
        properties.getBudget().setMaxTotalTokens(2000);
        DefaultBudgetGuard guard = new DefaultBudgetGuard(properties);
        String rootRunId = "block-budget";
        int threadCount = 4;
        int callsPerThread = 50;
        int tokensPerCall = 10;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < callsPerThread; i++) {
                        AgentContext context = new AgentContext();
                        context.setRunId(rootRunId);
                        context.setRootRunId(rootRunId);
                        guard.recordModelUsage(context, TokenUsage.builder()
                                .promptTokens(tokensPerCall)
                                .completionTokens(tokensPerCall)
                                .totalTokens(tokensPerCall * 2)
                                .build());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        AgentContext probe = new AgentContext();
        probe.setRunId(rootRunId);
        probe.setRootRunId(rootRunId);
        BudgetCheckResult result = guard.checkBeforeModelCall(probe, "node", "hello");
        long totalExpected = (long) threadCount * callsPerThread * tokensPerCall * 2;
        assertEquals(totalExpected, result.getUsedTokens());
        assertFalse("Should be blocked when usage exceeds budget", result.isAllowed());
    }
}
