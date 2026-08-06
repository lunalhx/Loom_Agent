package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.flow.AgentLoopPhase;
import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentErrorCode;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.WorkspaceResolutionException;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationExecutionGuard;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
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
    private final AgentRunLifecycle lifecycle;
    private final Executor executor;
    private final Map<String, AtomicBoolean> cancellationRequests = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> conversationRuns = new ConcurrentHashMap<>();
    private final ConversationExecutionGuard executionGuard;

    DefaultAgentLoopService(AgentLoopAssembly assembly, Executor executor,
                            AgentRunLifecycle lifecycle,
                            ConversationExecutionGuard executionGuard) {
        this.properties = assembly.properties();
        this.nodes = assembly.flow().nodes();
        this.components = assembly.components();
        this.lifecycle = lifecycle;
        this.executor = executor;
        this.executionGuard = executionGuard;
    }

    // ==================== 公共入口 ====================

    @Override
    public Flux<AgentEvent> ask(AgentQuestion question) {
        return executeAsync("ask", question == null ? null : question.getWorkspace(), (sink, capture) -> {
            AgentContext context = resolveContext(question);
            capture.accept(context);
            String lockKey = StringUtils.isBlank(context.getParentRunId())
                    ? ConversationExecutionGuard.effectiveLockKey(
                            context.getConversationId(), context.getRunId())
                    : null;
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
                emit(sink, lifecycle.initializeRun(context));
                emit(sink, List.of(components.eventFactory().meta(context)));
                emitSecretRedactionState(context);
                runLoop(context, sink);
            } finally {
                if (token != null) {
                    executionGuard.release(lockKey, token);
                }
            }
        });
    }

    private AgentContext resolveContext(AgentQuestion question) {
        return components.contextFactory().create(question);
    }

    /** When {@code secretRedaction} is explicitly disabled, record a trace
     *  security event so the behavior is never a silent default. */
    private void emitSecretRedactionState(AgentContext context) {
        try {
            cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties runProperties =
                    context.runtimeProperties(properties);
            if (runProperties.getFeatureFlags() != null
                    && !runProperties.getFeatureFlags().secretRedaction()) {
                components.nodeLifecycle().traceRecorder().recordSecurityEvent(context,
                        "secret_redaction_disabled", AgentNodeNames.PROMPT_BUILD, "warning",
                        Map.of("policy", "noop"));
            }
        } catch (Exception ignored) {
        }
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

    private void runLoop(AgentContext context, FluxSink<AgentEvent> sink) {
        runLoop(context, AgentNodeNames.PROMPT_BUILD, sink);
    }

    private void runLoop(AgentContext context, String startNode, FluxSink<AgentEvent> sink) {
        AtomicBoolean cancellation = new AtomicBoolean(false);
        AtomicBoolean existing = cancellationRequests.putIfAbsent(context.identity().runId(), cancellation);
        AtomicBoolean activeCancellation = existing == null ? cancellation : existing;
        String convId = context.identity().conversationId();
        if (convId != null) {
            conversationRuns.computeIfAbsent(convId, k -> ConcurrentHashMap.newKeySet()).add(context.identity().runId());
        }
        try {
            String currentNode = startNode;
            boolean firstRound = true;
            while (!sink.isCancelled() && !activeCancellation.get()) {
                if (!firstRound) {
                    if (isTotalTimeout(context)) {
                        finishTimeout(context, sink);
                        return;
                    }
                    if (context.getToolSteps() >= context.getMaxSteps()) {
                        finishStepLimit(context, sink);
                        return;
                    }
                    if (context.getModelAttempts() >= context.getMaxAttempts()) {
                        finishRetryLimit(context, sink);
                        return;
                    }
                }
                firstRound = false;

                AgentNode node = nodes.get(currentNode);
                context.runtime().enterNode(currentNode);
                if (node == null) {
                    context.runtime().fail(AgentStopReason.MODEL_ERROR, "node_not_found", "未知节点：" + currentNode);
                    finishFail(context, List.of(), sink);
                    return;
                }

                AgentNodeExecution execution =
                        components.nodeLifecycle().execute(context, node, events -> emit(sink, events));

                if (AgentNodeNames.MODEL_CALL.equals(currentNode)) {
                    lifecycle.recordModelAttempt(context);
                }
                if (AgentNodeNames.TOOL_OUTPUT.equals(currentNode)) {
                    emit(sink, lifecycle.checkpointAfterTool(context));
                }

                NodeResult result = execution.result();
                switch (result.getPhase()) {
                    case NEXT_NODE:
                        currentNode = execution.nextNode();
                        break;
                    case NEXT_ROUND:
                        currentNode = AgentNodeNames.PROMPT_BUILD;
                        break;
                    case COMPLETE:
                        finishComplete(context, result.getEvents(), sink);
                        return;
                    case PAUSE_USER_INPUT:
                        emit(sink, result.getEvents());
                        emit(sink, lifecycle.pauseForUserInput(context));
                        sink.complete();
                        return;
                    case FAIL:
                        finishFail(context, result.getEvents(), sink);
                        return;
                }
            }
            components.nodeLifecycle().cancelled(context, events -> emit(sink, events));
            lifecycle.cancelled(context);
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

    // ==================== 终止处理 ====================

    private void finishComplete(AgentContext context, List<AgentEvent> nodeEvents, FluxSink<AgentEvent> sink) {
        emit(sink, nodeEvents);
        String answer = StringUtils.defaultIfBlank(
                context.getDecision() == null ? null : context.getDecision().getAnswer(),
                StringUtils.defaultIfBlank(context.getFinalAnswer(), "未能生成最终回答"));
        context.runtime().complete(answer);
        context.setStopReason(AgentStopReason.FINAL_ANSWER_RETURNED);
        updateTaskSummary(context, answer);
        if (components.ledgerAppendService() != null) {
            components.ledgerAppendService().appendSystemNote(context, answer,
                    ConversationHistoryInitializer.eventKey(context.getRunId(),
                            String.valueOf(context.getToolSteps()), "final_answer"));
        }
        emit(sink, List.of(components.eventFactory().answer(context, answer)));
        emit(sink, List.of(components.eventFactory().done(context, AgentStopReason.FINAL_ANSWER_RETURNED)));
        components.nodeLifecycle().recordStop(context);
        lifecycle.complete(context);
        sink.complete();
    }

    private void finishStepLimit(AgentContext context, FluxSink<AgentEvent> sink) {
        String message = "已达到工具执行上限 (" + context.getMaxSteps() + " 步)，任务停止";
        context.runtime().stop(AgentStopReason.STEP_LIMIT_REACHED);
        context.setFinalAnswer(message);
        updateTaskSummary(context, message);
        if (components.ledgerAppendService() != null) {
            components.ledgerAppendService().appendSystemNote(context, message,
                    ConversationHistoryInitializer.eventKey(context.getRunId(),
                            String.valueOf(context.getToolSteps()), "step_limit"));
        }
        emit(sink, List.of(components.eventFactory().answer(context, message)));
        emit(sink, List.of(components.eventFactory().done(context, AgentStopReason.STEP_LIMIT_REACHED)));
        components.nodeLifecycle().recordStop(context);
        lifecycle.stopped(context);
        sink.complete();
    }

    private void finishRetryLimit(AgentContext context, FluxSink<AgentEvent> sink) {
        String message = "模型连续重试达到上限 (" + context.getMaxAttempts() + " 次)，已停止";
        context.runtime().stop(AgentStopReason.RETRY_LIMIT_REACHED);
        context.setFinalAnswer(message);
        updateTaskSummary(context, message);
        if (components.ledgerAppendService() != null) {
            components.ledgerAppendService().appendSystemNote(context, message,
                    ConversationHistoryInitializer.eventKey(context.getRunId(),
                            String.valueOf(context.getModelAttempts()), "retry_limit"));
        }
        emit(sink, List.of(components.eventFactory().answer(context, message)));
        emit(sink, List.of(components.eventFactory().done(context, AgentStopReason.RETRY_LIMIT_REACHED)));
        components.nodeLifecycle().recordStop(context);
        lifecycle.stopped(context);
        sink.complete();
    }

    private void finishTimeout(AgentContext context, FluxSink<AgentEvent> sink) {
        context.runtime().fail(AgentStopReason.TIMEOUT, AgentErrorCode.AGENT_TIMEOUT.code(), "Agent 执行超时");
        finishFail(context, List.of(), sink);
    }

    private void finishFail(AgentContext context, List<AgentEvent> nodeEvents, FluxSink<AgentEvent> sink) {
        emit(sink, nodeEvents);
        emit(sink, List.of(components.eventFactory().agentError(context)));
        components.nodeLifecycle().recordStop(context);
        lifecycle.failed(context);
        sink.complete();
    }

    // ==================== 私有辅助 ====================

    /** Keep the durable working-memory task summary in sync with the final outcome. */
    private void updateTaskSummary(AgentContext context, String outcome) {
        try {
            WorkingContextMemory wm = context.workingMemoryOrCreate();
            wm.setTaskSummary(StringUtils.abbreviate(outcome, 300));
        } catch (Exception ignored) {
            // best-effort; never break termination for a memory update
        }
    }

    private boolean isTotalTimeout(AgentContext context) {
        return Duration.between(context.runtime().startedAt(), Instant.now()).toMillis()
                > context.runtimeProperties(properties).getTotalTimeoutMs();
    }

    private void emit(FluxSink<AgentEvent> sink, List<AgentEvent> events) {
        for (AgentEvent event : events) {
            if (!sink.isCancelled()) {
                sink.next(event);
            }
        }
    }
}
