package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentResult;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

class SubAgentExecutionScheduler {

    private final Executor executor;
    private final SubAgentResultFactory resultFactory;

    SubAgentExecutionScheduler(Executor executor, SubAgentResultFactory resultFactory) {
        this.executor = executor;
        this.resultFactory = resultFactory;
    }

    List<SubAgentResult> schedule(SubAgentDispatchPlan plan, AgentContext parent, TaskRunner runner) {
        Semaphore semaphore = new Semaphore(plan.concurrency());
        List<CompletableFuture<SubAgentResult>> futures = new ArrayList<>();
        for (int i = 0; i < plan.tasks().size(); i++) {
            SubAgentTask task = plan.tasks().get(i);
            int ordinal = i + 1;
            futures.add(CompletableFuture.supplyAsync(() -> runWithPermit(parent, task, ordinal, semaphore, runner), executor));
        }

        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        try {
            all.get(plan.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            futures.forEach(future -> future.cancel(true));
        }

        List<SubAgentResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<SubAgentResult> future = futures.get(i);
            SubAgentTask task = plan.tasks().get(i);
            if (future.isDone() && !future.isCancelled()) {
                try {
                    results.add(future.getNow(resultFactory.timeout(task, null, 0L)));
                } catch (Exception e) {
                    results.add(resultFactory.failed(task, null, "sub_agent_failed", e.getMessage(), 0L));
                }
            } else {
                future.cancel(true);
                results.add(resultFactory.timeout(task, null, plan.timeoutMs()));
            }
        }
        return results;
    }

    private SubAgentResult runWithPermit(AgentContext parent,
                                         SubAgentTask task,
                                         int ordinal,
                                         Semaphore semaphore,
                                         TaskRunner runner) {
        long startedAt = System.currentTimeMillis();
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;
            return runner.run(parent, task, ordinal, startedAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return resultFactory.interrupted(task, System.currentTimeMillis() - startedAt);
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    interface TaskRunner {
        SubAgentResult run(AgentContext parent, SubAgentTask task, int ordinal, long startedAt);
    }
}
