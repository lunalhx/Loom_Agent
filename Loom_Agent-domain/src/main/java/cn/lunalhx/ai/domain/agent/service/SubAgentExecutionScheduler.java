package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.SubAgentControlInbox;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentControlMessage;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentResult;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentTask;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.SubAgentControlMessageType;
import cn.lunalhx.ai.domain.agent.model.valobj.SubAgentStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class SubAgentExecutionScheduler {

    private final Executor executor;
    private final SubAgentResultFactory resultFactory;
    private final AgentRuntimeProperties properties;
    private final SubAgentControlInbox inbox;

    SubAgentExecutionScheduler(Executor executor,
                               SubAgentResultFactory resultFactory,
                               AgentRuntimeProperties properties) {
        this.executor = executor;
        this.resultFactory = resultFactory;
        this.properties = properties;
        this.inbox = new SubAgentControlInbox() {
            private final java.util.concurrent.ConcurrentHashMap<String, java.util.List<SubAgentControlMessage>> store =
                    new java.util.concurrent.ConcurrentHashMap<>();

            @Override
            public void send(String childRunId, SubAgentControlMessage message) {
                store.computeIfAbsent(childRunId, k -> new java.util.ArrayList<>()).add(message);
            }

            @Override
            public java.util.List<SubAgentControlMessage> poll(String childRunId) {
                java.util.List<SubAgentControlMessage> messages = store.getOrDefault(childRunId, java.util.List.of());
                java.util.List<SubAgentControlMessage> active = new java.util.ArrayList<>();
                for (SubAgentControlMessage m : messages) {
                    if (m.getDeadlineMs() > System.currentTimeMillis()) {
                        active.add(m);
                    }
                }
                return active;
            }

            @Override
            public void clear(String childRunId) {
                store.remove(childRunId);
            }
        };
    }

    SubAgentControlInbox inbox() {
        return inbox;
    }

    List<SubAgentResult> schedule(SubAgentDispatchPlan plan, AgentContext parent, TaskRunner runner) {
        Semaphore semaphore = new Semaphore(plan.concurrency());
        List<SubAgentRunHandle> handles = new ArrayList<>();
        for (int i = 0; i < plan.tasks().size(); i++) {
            SubAgentTask task = plan.tasks().get(i);
            int ordinal = i + 1;
            String childRunId = UUID.randomUUID().toString();
            CompletableFuture<SubAgentResult> future = CompletableFuture.supplyAsync(
                    () -> runWithPermit(parent, task, ordinal, childRunId, semaphore, runner), executor);
            handles.add(new SubAgentRunHandle(task, ordinal, childRunId, future,
                    System.currentTimeMillis(), "RUNNING"));
        }

        CompletableFuture<Void> all = CompletableFuture.allOf(
                handles.stream().map(SubAgentRunHandle::future).toArray(CompletableFuture[]::new));
        try {
            all.get(plan.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return recoverAndCollect(handles, plan, parent);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handles.forEach(h -> h.future().cancel(true));
        } catch (ExecutionException e) {
            handles.forEach(h -> h.future().cancel(true));
        }

        return collectResults(handles, plan);
    }

    private List<SubAgentResult> recoverAndCollect(List<SubAgentRunHandle> handles,
                                                    SubAgentDispatchPlan plan,
                                                    AgentContext parent) {
        if (!Boolean.TRUE.equals(properties.getSubAgentRecoveryEnabled())) {
            handles.forEach(h -> h.future().cancel(true));
            return collectTimeoutResults(handles, plan);
        }

        long recoveryMs = positive(properties.getSubAgentIdleRecoveryMs(), 60000L);
        long deadline = System.currentTimeMillis() + recoveryMs;

        for (SubAgentRunHandle handle : handles) {
            if (!handle.future().isDone()) {
                if (inbox != null) {
                    inbox.send(handle.childRunId(), SubAgentControlMessage.builder()
                            .type(SubAgentControlMessageType.GRACEFUL_STOP_REQUESTED)
                            .childRunId(handle.childRunId())
                            .deadlineMs(deadline)
                            .reason("sub_agent_timeout_recovery")
                            .build());
                }
            }
        }

        long pollInterval = positive(properties.getSubAgentRecoveryPollIntervalMs(), 1000L);
        for (SubAgentRunHandle handle : handles) {
            if (handle.future().isDone()) {
                continue;
            }
            long waitUntil = Math.min(deadline, System.currentTimeMillis() + pollInterval);
            while (System.currentTimeMillis() < waitUntil) {
                if (handle.future().isDone()) {
                    break;
                }
                try {
                    long remaining = waitUntil - System.currentTimeMillis();
                    if (remaining > 0) {
                        Thread.sleep(Math.min(100L, remaining));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        for (SubAgentRunHandle handle : handles) {
            if (!handle.future().isDone()) {
                handle.future().cancel(true);
            }
        }

        if (inbox != null) {
            handles.forEach(h -> inbox.clear(h.childRunId()));
        }

        return collectResults(handles, plan);
    }

    private List<SubAgentResult> collectResults(List<SubAgentRunHandle> handles, SubAgentDispatchPlan plan) {
        List<SubAgentResult> results = new ArrayList<>();
        for (int i = 0; i < handles.size(); i++) {
            SubAgentRunHandle handle = handles.get(i);
            SubAgentTask task = plan.tasks().get(i);
            long elapsed = System.currentTimeMillis() - handle.startedAt();

            if (handle.future().isDone() && !handle.future().isCancelled()) {
                try {
                    SubAgentResult result = handle.future().get();
                    results.add(result != null ? result
                            : resultFactory.timeout(task, handle.childRunId(), elapsed));
                } catch (ExecutionException e) {
                    results.add(resultFactory.failed(task, handle.childRunId(),
                            "sub_agent_failed", e.getMessage(), elapsed));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(resultFactory.interrupted(task, elapsed));
                }
            } else if (handle.future().isCancelled()) {
                results.add(resultFactory.timeout(task, handle.childRunId(), elapsed));
            } else {
                results.add(resultFactory.timeout(task, handle.childRunId(), elapsed));
            }
        }
        return results;
    }

    private List<SubAgentResult> collectTimeoutResults(List<SubAgentRunHandle> handles,
                                                        SubAgentDispatchPlan plan) {
        List<SubAgentResult> results = new ArrayList<>();
        for (int i = 0; i < handles.size(); i++) {
            SubAgentRunHandle handle = handles.get(i);
            SubAgentTask task = plan.tasks().get(i);
            long elapsed = System.currentTimeMillis() - handle.startedAt();
            results.add(resultFactory.timeout(task, handle.childRunId(), elapsed));
        }
        return results;
    }

    private SubAgentResult runWithPermit(AgentContext parent,
                                         SubAgentTask task,
                                         int ordinal,
                                         String childRunId,
                                         Semaphore semaphore,
                                         TaskRunner runner) {
        long startedAt = System.currentTimeMillis();
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;
            return runner.run(parent, task, ordinal, childRunId, startedAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return resultFactory.interrupted(task, System.currentTimeMillis() - startedAt);
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private long positive(Long value, long fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    interface TaskRunner {
        SubAgentResult run(AgentContext parent, SubAgentTask task, int ordinal, String childRunId, long startedAt);
    }
}
