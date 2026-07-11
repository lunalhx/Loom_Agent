package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.state.AgentRuntimeState;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentErrorCode;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.UserInputAction;
import cn.lunalhx.ai.domain.agent.model.valobj.WorkspaceResolutionException;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationExecutionGuard;
import cn.lunalhx.ai.domain.agent.service.undo.UndoSessionCoordinator;
import cn.lunalhx.ai.domain.agent.service.undo.UndoSessionCoordinator.WorkspaceUndoBusyException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

@Slf4j
public class DefaultAgentLoopService implements AgentLoopService {

    private final AgentRuntimeProperties properties;
    private final Map<String, AgentNode> nodes;
    private final AgentLoopComponents components;
    private final Executor executor;
    private final UndoSessionCoordinator undoCoordinator;
    private final Map<String, AtomicBoolean> cancellationRequests = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> conversationRuns = new ConcurrentHashMap<>();
    private final ConversationExecutionGuard executionGuard;

    // ==================== 生产构造器 ====================

    DefaultAgentLoopService(AgentLoopAssembly assembly, Executor executor, ConversationExecutionGuard executionGuard) {
        this.properties = assembly.properties();
        this.nodes = assembly.flow().nodes();
        this.components = assembly.components();
        this.executor = executor;
        this.undoCoordinator = assembly.undoCoordinator();
        this.executionGuard = executionGuard;
    }

    // ==================== 公共入口 ====================

    @Override
    public Flux<AgentEvent> ask(AgentQuestion question) {
        return executeAsync("ask", question == null ? null : question.getWorkspace(), (sink, capture) -> {
            AgentContext context = resolveContext(question);
            capture.accept(context);
            String lockKey = ConversationExecutionGuard.effectiveLockKey(
                    context.getConversationId(), context.getRunId());
            String token = null;
            if (lockKey != null) {
                token = executionGuard.tryAcquire(lockKey);
                if (token == null) {
                    emit(sink, List.of(components.eventFactory().conversationBusy(
                            context.getConversationId(), context.getRunId(), context.getRequestId(),
                            null, "ask", Instant.now())));
                    sink.complete();
                    return;
                }
            }
            try {
                emit(sink, List.of(components.eventFactory().runStarted(context)));
                if (undoCoordinator != null) {
                    undoCoordinator.onRunStart(context);
                }
                try {
                    components.nodeLifecycle().userPromptSubmitted(context, events -> emit(sink, events));
                    runLoop(context, AgentNodeNames.SKILL_BOOTSTRAP, sink);
                } catch (WorkspaceUndoBusyException e) {
                    emit(sink, List.of(components.eventFactory().workspaceUndoBusy(context, e)));
                    components.nodeLifecycle().persistFailure(context, events -> emit(sink, events));
                    sink.complete();
                    return;
                } catch (Exception e) {
                    if (undoCoordinator != null) {
                        undoCoordinator.onRunFailed(context);
                    }
                    throw e;
                }
            } finally {
                if (token != null) {
                    executionGuard.release(lockKey, token);
                }
            }
        });
    }

    private AgentContext resolveContext(AgentQuestion question) {
        String conversationId = question.getConversationId();
        if (StringUtils.isBlank(conversationId)) {
            return components.contextFactory().create(question);
        }

        AgentRun previousRun = components.runRepository().findLatestRootByConversationId(conversationId).orElse(null);
        if (previousRun == null) {
            return components.contextFactory().create(question);
        }

        AgentCheckpoint checkpoint = components.checkpointRepository().latest(previousRun.getRunId()).orElse(null);
        AgentContextSnapshot previous = checkpoint != null ? checkpoint.getContextSnapshot() : null;
        return components.contextFactory().createContinuation(question, previous);
    }

    @Override
    public Flux<AgentEvent> resume(String approvalId, ApprovalDecision decision, String reason) {
        return resume(approvalId, decision, reason, null, List.of());
    }

    @Override
    public Flux<AgentEvent> resume(
            String approvalId,
            ApprovalDecision decision,
            String reason,
            String reasonCode,
            List<String> allowedAlternatives) {
        return executeAsync("resume", approvalId, (sink, capture) -> {
            PendingApproval approval = components.approvalStore().find(approvalId).orElse(null);
            String convId = null;
            String runId = null;
            if (approval != null) {
                if (approval.getContext() != null) {
                    convId = approval.getContext().getConversationId();
                    runId = approval.getContext().getRunId();
                } else {
                    runId = approval.getRunId();
                }
            }
            String lockKey = ConversationExecutionGuard.effectiveLockKey(convId, runId);
            String token = null;
            if (lockKey != null) {
                token = executionGuard.tryAcquire(lockKey);
                if (token == null) {
                    emit(sink, List.of(components.eventFactory().conversationBusy(
                            convId, runId, null, null, "resume_approval", Instant.now())));
                    sink.complete();
                    return;
                }
            }
            try {
                AgentResumePlan plan = components.resumeCoordinator().prepareApprovalResume(
                        approvalId, decision, reason, reasonCode, allowedAlternatives);
                capture.accept(plan.context());
                continueFrom(plan, sink);
            } finally {
                if (token != null) {
                    executionGuard.release(lockKey, token);
                }
            }
        });
    }

    @Override
    public Flux<AgentEvent> resumeRun(String runId) {
        return executeAsync("resumeRun", runId, (sink, capture) -> {
            AgentRun run = components.runRepository().find(runId).orElse(null);
            String convId = run != null ? run.getConversationId() : null;
            String lockKey = ConversationExecutionGuard.effectiveLockKey(convId, runId);
            String token = null;
            if (lockKey != null) {
                token = executionGuard.tryAcquire(lockKey);
                if (token == null) {
                    emit(sink, List.of(components.eventFactory().conversationBusy(
                            convId, runId, null, run != null ? run.getRunId() : null,
                            "resumeRun", Instant.now())));
                    sink.complete();
                    return;
                }
            }
            try {
                AgentResumePlan plan = components.resumeCoordinator().prepareRunResume(runId);
                capture.accept(plan.context());
                continueFrom(plan, sink);
            } finally {
                if (token != null) {
                    executionGuard.release(lockKey, token);
                }
            }
        });
    }

    @Override
    public Flux<AgentEvent> resumeWithUserInput(String runId, UserInputAction action, String message) {
        return executeAsync("resumeWithUserInput", runId, (sink, capture) -> {
            AgentRun run = components.runRepository().find(runId).orElse(null);
            String convId = run != null ? run.getConversationId() : null;
            String lockKey = ConversationExecutionGuard.effectiveLockKey(convId, runId);
            String token = null;
            if (lockKey != null) {
                token = executionGuard.tryAcquire(lockKey);
                if (token == null) {
                    emit(sink, List.of(components.eventFactory().conversationBusy(
                            convId, runId, null, run != null ? run.getRunId() : null,
                            "resumeWithUserInput", Instant.now())));
                    sink.complete();
                    return;
                }
            }
            try {
                AgentResumePlan plan = components.resumeCoordinator().prepareUserInputResume(runId, action, message);
                capture.accept(plan.context());
                continueFrom(plan, sink);
            } finally {
                if (token != null) {
                    executionGuard.release(lockKey, token);
                }
            }
        });
    }

    @Override
    public boolean cancelRun(String runId) {
        if (StringUtils.isBlank(runId)) {
            return false;
        }
        AtomicBoolean cancellation = cancellationRequests.get(runId);
        return cancellation != null && cancellation.compareAndSet(false, true);
    }

    @Override
    public void cancelConversation(String conversationId) {
        if (StringUtils.isBlank(conversationId)) {
            return;
        }
        Set<String> runIds = conversationRuns.getOrDefault(conversationId, Set.of());
        for (String runId : runIds) {
            cancelRun(runId);
        }
    }

    @Override
    public boolean hasActiveRuns(String conversationId) {
        if (StringUtils.isBlank(conversationId)) {
            return false;
        }
        Set<String> runIds = conversationRuns.get(conversationId);
        if (runIds == null || runIds.isEmpty()) {
            return false;
        }
        for (String runId : runIds) {
            AtomicBoolean c = cancellationRequests.get(runId);
            if (c != null && !c.get()) {
                return true;
            }
        }
        return false;
    }

    // ==================== 核心编排 ====================

    private Flux<AgentEvent> executeAsync(String operation, String reference,
                                          BiConsumer<FluxSink<AgentEvent>, java.util.function.Consumer<AgentContext>> action) {
        return Flux.create(sink -> executor.execute(() -> {
            AtomicReference<AgentContext> activeContext = new AtomicReference<>();
            try {
                action.accept(sink, activeContext::set);
            } catch (WorkspaceResolutionException e) {
                emit(sink, List.of(components.eventFactory().workspaceError(e)));
                sink.complete();
            } catch (Exception e) {
                log.error("Agent loop failed before terminal event, operation={}, reference={}",
                        operation,
                        reference == null ? null : StringUtils.abbreviate(reference, 200),
                        e);
                emit(sink, List.of(components.eventFactory().agentError(activeContext.get())));
                sink.complete();
            } finally {
                MDC.clear();
            }
        }), FluxSink.OverflowStrategy.BUFFER);
    }

    private void continueFrom(AgentResumePlan plan, FluxSink<AgentEvent> sink) {
        emit(sink, plan.initialEvents());
        if (plan.terminal()) {
            sink.complete();
            return;
        }

        if (undoCoordinator != null && plan.context() != null) {
            String resumeError = undoCoordinator.onRunResume(plan.context());
            if (resumeError != null) {
                emit(sink, List.of(components.eventFactory().agentError()));
                sink.complete();
                return;
            }
        }

        try {
            runLoop(plan.context(), plan.startNode(), sink);
        } catch (Exception e) {
            if (undoCoordinator != null) {
                undoCoordinator.onRunFailed(plan.context());
            }
            throw e;
        }
    }

    private void runLoop(AgentContext context, String currentNode, FluxSink<AgentEvent> sink) {
        AtomicBoolean cancellation = new AtomicBoolean(false);
        AtomicBoolean existing = cancellationRequests.putIfAbsent(context.identity().runId(), cancellation);
        AtomicBoolean activeCancellation = existing == null ? cancellation : existing;
        String convId = context.identity().conversationId();
        if (convId != null) {
            conversationRuns.computeIfAbsent(convId, k -> ConcurrentHashMap.newKeySet()).add(context.identity().runId());
        }
        try {
            while (!sink.isCancelled() && !activeCancellation.get()) {
                if (isTotalTimeout(context)) {
                    context.runtime().fail(AgentStopReason.TIMEOUT, AgentErrorCode.AGENT_TIMEOUT.code(), "Agent 执行超时");
                    currentNode = AgentNodeNames.FAIL;
                }

                AgentNode node = nodes.get(currentNode);
                context.runtime().enterNode(currentNode);
                if (node == null) {
                    context.runtime().fail(AgentStopReason.MODEL_ERROR, "node_not_found", "未知节点：" + currentNode);
                    node = nodes.get(AgentNodeNames.FAIL);
                    if (node == null) {
                        log.error("FAIL 节点缺失，无法继续执行。currentNode={}", currentNode);
                        sink.complete();
                        return;
                    }
                }

                AgentNodeExecution execution =
                        components.nodeLifecycle().execute(context, node, events -> emit(sink, events));

                if (execution.terminal() && execution.hasDeferredTerminalEvents()) {
                    execution = components.nodeLifecycle().resolveStop(
                            context, node, execution.terminalEvents(), events -> emit(sink, events));
                }

                if (execution.isStopContinued()) {
                    currentNode = execution.nextNode();
                    continue;
                }

                if (execution.terminal()) {
                    sink.complete();
                    return;
                }

                currentNode = execution.nextNode();
            }
            components.nodeLifecycle().cancelled(context, events -> emit(sink, events));
            sink.complete();
        } finally {
            if (existing == null) {
                cancellationRequests.remove(context.identity().runId(), cancellation);
            }
            if (convId != null) {
                Set<String> runIds = conversationRuns.get(convId);
                if (runIds != null) {
                    runIds.remove(context.identity().runId());
                    if (runIds.isEmpty()) {
                        conversationRuns.remove(convId, runIds);
                    }
                }
            }
        }
    }

    // ==================== 私有辅助 ====================

    private boolean isTotalTimeout(AgentContext context) {
        return Duration.between(context.runtime().startedAt(), Instant.now()).toMillis() > properties.getTotalTimeoutMs();
    }

    private void emit(FluxSink<AgentEvent> sink, List<AgentEvent> events) {
        for (AgentEvent event : events) {
            if (!sink.isCancelled()) {
                sink.next(event);
            }
        }
    }
}
