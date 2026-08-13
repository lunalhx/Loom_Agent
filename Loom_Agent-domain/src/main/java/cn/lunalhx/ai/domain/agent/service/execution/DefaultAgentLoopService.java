package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunStartGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.AttemptLeaseRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.PlanSubmissionHandler;
import cn.lunalhx.ai.domain.agent.adapter.port.PlanSubmissionResult;
import cn.lunalhx.ai.domain.agent.model.entity.AttemptLease;
import cn.lunalhx.ai.domain.agent.flow.AgentLoopPhase;
import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.PlanDeviation;
import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentErrorCode;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.model.valobj.WorkspaceResolutionException;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationExecutionGuard;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
import cn.lunalhx.ai.domain.skill.model.SkillActivationException;
import cn.lunalhx.ai.domain.agent.service.recovery.RecoveredToolCatalog;
import cn.lunalhx.ai.domain.skill.service.SkillRunBootstrap;
import cn.lunalhx.ai.domain.skill.service.SkillToolCatalogProjector;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.ObservationTools;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private final SkillRunBootstrap skillRunBootstrap;
    private final ToolRegistry toolRegistry;
    private final AttemptLeaseRepository leaseRepository;

    DefaultAgentLoopService(AgentLoopAssembly assembly, Executor executor,
                            AgentRunLifecycle lifecycle,
                            ConversationExecutionGuard executionGuard,
                            ToolRegistry toolRegistry) {
        this.properties = assembly.properties();
        this.nodes = assembly.flow().nodes();
        this.components = assembly.components();
        this.lifecycle = lifecycle;
        this.executor = executor;
        this.executionGuard = executionGuard;
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.skillRunBootstrap = new SkillRunBootstrap(properties, toolRegistry);
        this.leaseRepository = lifecycle.attemptLeaseRepository();
    }

    // ==================== 公共入口 ====================

    @Override
    public Flux<AgentEvent> ask(AgentQuestion question) {
        return executeAsync("ask", question == null ? null : question.getWorkspace(), (sink, capture) -> {
            AutoCloseable startLease = null;
            try {
                if (isTerminalRun(question)) {
                    rejectTerminalRun(question, sink);
                    return;
                }
                AgentRunStartGuard startGuard = question == null ? null : question.getRunStartGuard();
                if (startGuard != null) {
                    try {
                        startLease = startGuard.acquire();
                    } catch (Exception e) {
                        throw new IllegalStateException("Run start authorization failed", e);
                    }
                }
                AgentContext context;
                boolean continuing = false;
                if (canContinueFromCheckpoint(question)) {
                    context = components.contextFactory().restoreFromCheckpoint(
                            question,
                            components.checkpointRepository().latest(question.getRunId())
                                    .orElseThrow().getContextSnapshot());
                    continuing = true;
                } else {
                    context = components.contextFactory().create(question);
                }
                capture.accept(context);
                try {
                    skillRunBootstrap.prepareRun(context, null);
                    context.setToolSpecs(SkillToolCatalogProjector.project(context, toolRegistry));
                    if (continuing) {
                        List<ToolSpec> recovered = RecoveredToolCatalog.keepCompatible(
                                context.getFrozenToolContracts(), context.getToolSpecs());
                        context.setToolSpecs(recovered);
                        context.setAllowedTools(recovered.stream()
                                .map(ToolSpec::getName)
                                .toList());
                    }
                } catch (SkillActivationException e) {
                    context.runtime().fail(AgentStopReason.MODEL_ERROR, "skill_activation_failed",
                            e.getMessage());
                    emit(sink, List.of(components.eventFactory().agentError(context)));
                    sink.complete();
                    return;
                }
                Optional<AttemptLease> claimed = claimAttempt(context);
                if (claimed.isEmpty()) {
                    context.runtime().fail(AgentStopReason.RESUME_ERROR, "attempt_lease_held",
                            "A healthy Attempt still owns this Run");
                    emit(sink, List.of(components.eventFactory().agentError(context)));
                    sink.complete();
                    return;
                }
                ScheduledExecutorService heartbeats = startHeartbeat(context);
                String lockKey = StringUtils.isBlank(context.getParentRunId())
                        ? ConversationExecutionGuard.effectiveLockKey(
                                context.getConversationId(), context.getRunId())
                        : null;
                String token = null;
                if (lockKey != null) {
                    token = executionGuard.tryAcquire(lockKey);
                    if (token == null) {
                        stopHeartbeat(heartbeats);
                        releaseLeaseQuietly(context);
                        emit(sink, List.of(components.eventFactory().conversationBusy(
                                context.getConversationId(), context.getRunId(), context.getRequestId(),
                                null, "ask", Instant.now())));
                        return;
                    }
                }
                try {
                    emit(sink, List.of(components.eventFactory().runStarted(context)));
                    if (continuing) {
                        context.setCurrentNode(AgentNodeNames.PROMPT_BUILD);
                        emit(sink, lifecycle.resumeRunning(context));
                        emit(sink, lifecycle.reconcileToolDurability(context));
                    } else {
                        emit(sink, lifecycle.initializeRun(context));
                    }
                    emit(sink, List.of(components.eventFactory().meta(context)));
                    emitSecretRedactionState(context);
                    closeQuietly(startLease);
                    startLease = null;
                    runLoop(context, AgentNodeNames.PROMPT_BUILD, sink);
                } finally {
                    stopHeartbeat(heartbeats);
                    releaseLeaseQuietly(context);
                    if (token != null) {
                        executionGuard.release(lockKey, token);
                    }
                }
            } finally {
                closeQuietly(startLease);
            }
        });
    }

    private Optional<AttemptLease> claimAttempt(AgentContext context) {
        String attemptId = "attempt_" + UUID.randomUUID().toString().substring(0, 8);
        Optional<AttemptLease> claimed = leaseRepository.tryAcquire(context.getRunId(), attemptId);
        claimed.ifPresent(lease -> {
            context.setAttemptId(lease.getAttemptId());
            context.setLeaseFence(lease.getFence());
        });
        return claimed;
    }

    private ScheduledExecutorService startHeartbeat(AgentContext context) {
        ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(thread -> {
            Thread worker = new Thread(thread, "attempt-lease-heartbeat");
            worker.setDaemon(true);
            return worker;
        });
        heartbeats.scheduleAtFixedRate(() -> {
            try {
                leaseRepository.heartbeat(context.getRunId(), context.getLeaseFence());
            } catch (RuntimeException ignored) {
            }
        }, 5, 5, TimeUnit.SECONDS);
        return heartbeats;
    }

    private void stopHeartbeat(ScheduledExecutorService heartbeats) {
        if (heartbeats == null) {
            return;
        }
        heartbeats.shutdownNow();
    }

    private void releaseLeaseQuietly(AgentContext context) {
        if (context == null || context.getLeaseFence() == null) {
            return;
        }
        try {
            leaseRepository.release(context.getRunId(), context.getLeaseFence());
        } catch (RuntimeException ignored) {
        }
    }

    private void closeQuietly(AutoCloseable lease) {
        if (lease == null) {
            return;
        }
        try {
            lease.close();
        } catch (Exception ignored) {
        }
    }

    private AgentContext resolveContext(AgentQuestion question) {
        return components.contextFactory().create(question);
    }

    private boolean canContinueFromCheckpoint(AgentQuestion question) {
        if (question == null || StringUtils.isBlank(question.getRunId())) {
            return false;
        }
        return components.runRepository().find(question.getRunId())
                .filter(run -> !run.getStatus().terminal())
                .flatMap(run -> components.checkpointRepository().latest(run.getRunId()))
                .map(checkpoint -> checkpoint.getContextSnapshot() != null)
                .orElse(false);
    }

    private boolean isTerminalRun(AgentQuestion question) {
        if (question == null || StringUtils.isBlank(question.getRunId())) {
            return false;
        }
        return components.runRepository().find(question.getRunId())
                .map(AgentRun::getStatus)
                .filter(AgentRunStatus::terminal)
                .isPresent();
    }

    private void rejectTerminalRun(AgentQuestion question, FluxSink<AgentEvent> sink) {
        AgentContext context = resolveContext(question);
        String message = "Run is already terminal and cannot be resumed";
        context.runtime().fail(AgentStopReason.RUNTIME_SCHEMA_MISMATCH,
                "terminal_run_reentry", message);
        context.setFinalAnswer(message);
        emit(sink, List.of(components.eventFactory().agentError(context)));
        emit(sink, List.of(components.eventFactory().done(
                context, AgentStopReason.RUNTIME_SCHEMA_MISMATCH)));
        sink.complete();
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
            } catch (Error e) {
                if (e instanceof VirtualMachineError) {
                    throw e;
                }
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
        if (context.getSecurityScope() != null) {
            sink.onCancel(context.getSecurityScope()::cancel);
        }
        String convId = context.identity().conversationId();
        if (convId != null) {
            conversationRuns.computeIfAbsent(convId, k -> ConcurrentHashMap.newKeySet()).add(context.identity().runId());
        }
        try {
            String currentNode = startNode;
            boolean firstRound = true;
            boolean persistHistoryAfterFirstPrompt = true;
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

                if (persistHistoryAfterFirstPrompt && AgentNodeNames.PROMPT_BUILD.equals(currentNode)) {
                    lifecycle.persistHistoryAfterPrompt(context);
                    persistHistoryAfterFirstPrompt = false;
                }
                if (AgentNodeNames.MODEL_CALL.equals(currentNode)) {
                    lifecycle.recordModelAttempt(context);
                }
                if (AgentNodeNames.TOOL_INPUT.equals(currentNode)
                        && context.getAuthorizedToolCall() != null
                        && ObservationTools.isObservation(context.getAuthorizedToolCall().toolName())) {
                    emit(sink, lifecycle.openExecutionWindow(context));
                }
                if (AgentNodeNames.TOOL_OUTPUT.equals(currentNode)) {
                    emit(sink, lifecycle.checkpointAfterTool(context));
                }

                if (AgentNodeNames.DECISION.equals(currentNode)
                        && context.getDecision() != null
                        && "skill_activation".equals(context.getDecision().getType())
                        && context.getToolResult() == null) {
                    emit(sink, lifecycle.checkpointAfterSkillActivation(context));
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
            if (context.getParentRunId() == null && context.getSecurityScope() != null) {
                context.getSecurityScope().close();
            }
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
        if (context.getDecision() != null
                && "plan_submission".equals(context.getDecision().getType())) {
            finishPlanSubmission(context, sink);
            return;
        }
        if (context.getDecision() != null
                && "plan_deviation".equals(context.getDecision().getType())) {
            finishPlanDeviation(context, sink);
            return;
        }
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

    private void finishPlanDeviation(AgentContext context, FluxSink<AgentEvent> sink) {
        PlanDeviation deviation = context.getDecision().getPlanDeviation();
        if (!isEligiblePlanDeviation(context) || !isWellFormedPlanDeviation(deviation)) {
            String message = "Plan Deviation rejected: only a root Build Run with an immutable Plan binding may report it";
            context.runtime().fail(AgentStopReason.RUNTIME_SCHEMA_MISMATCH,
                    "plan_deviation_rejected", message);
            context.setFinalAnswer(message);
            updateTaskSummary(context, message);
            emit(sink, List.of(components.eventFactory().agentError(context)));
            emit(sink, List.of(components.eventFactory().done(
                    context, AgentStopReason.RUNTIME_SCHEMA_MISMATCH)));
            components.nodeLifecycle().recordStop(context);
            lifecycle.failed(context);
            sink.complete();
            return;
        }

        String message = renderPlanDeviation(deviation);
        context.stopRun(AgentStopReason.PLAN_DEVIATION);
        context.setFinalAnswer(message);
        updateTaskSummary(context, message);
        emit(sink, List.of(components.eventFactory().answer(context, message)));
        emit(sink, List.of(components.eventFactory().done(
                context, AgentStopReason.PLAN_DEVIATION)));
        components.nodeLifecycle().recordStop(context);
        lifecycle.stopped(context);
        sink.complete();
    }

    private boolean isEligiblePlanDeviation(AgentContext context) {
        return context.getCollaborationMode() == CollaborationMode.BUILD
                && StringUtils.isBlank(context.getParentRunId())
                && context.getPlanBinding() != null
                && context.getPlanBinding().isIssuedByPlanHandoff();
    }

    private boolean isWellFormedPlanDeviation(PlanDeviation deviation) {
        if (deviation == null || deviation.getConflict() == null
                || deviation.getConflict().getKind() == null
                || deviation.getConflict().getSummary() == null
                || deviation.getConflict().getSummary().isBlank()
                || !PlanDeviation.isSupportedConflictKind(deviation.getConflict().getKind())) {
            return false;
        }
        if (deviation.getWorkspaceChanges() == null) {
            return false;
        }
        return deviation.getWorkspaceChanges().stream().allMatch(change ->
                change != null
                        && PlanDeviation.isValidWorkspacePath(change.getPath())
                        && PlanDeviation.isSupportedOperation(change.getOperation())
                        && change.getSummary() != null
                        && !change.getSummary().isBlank());
    }

    private String renderPlanDeviation(PlanDeviation deviation) {
        String changes = deviation.getWorkspaceChanges().isEmpty()
                ? "none"
                : deviation.getWorkspaceChanges().stream()
                .map(change -> change.getPath() + " (" + change.getOperation() + ")")
                .collect(java.util.stream.Collectors.joining(", "));
        return "Plan Deviation: " + deviation.getConflict().getKind()
                + ": " + deviation.getConflict().getSummary()
                + "; workspace changes: " + changes;
    }

    private void finishPlanSubmission(AgentContext context, FluxSink<AgentEvent> sink) {
        PlanSubmissionHandler handler = components.planSubmissionHandler();
        PlanSubmissionResult result;
        try {
            result = handler.prepare(context);
        } catch (Exception e) {
            result = PlanSubmissionResult.conflict(
                    "Plan Conflict: persistence or validation failed: " + e.getMessage());
        }

        if (result != null && result.outcome() == PlanSubmissionResult.Outcome.PREPARED) {
            String message = "Plan submitted: " + result.planId()
                    + " revision " + result.revision();
            context.runtime().complete(message);
            context.setStopReason(AgentStopReason.PLAN_SUBMITTED);
            context.setFinalAnswer(message);
            updateTaskSummary(context, message);
            components.nodeLifecycle().recordStop(context);
            boolean terminalRunDurable = false;
            try {
                // The Run outcome is durable before the visible Plan
                // aggregate is committed. A pending Session transaction lets
                // recovery finish the second phase after interruption.
                lifecycle.complete(context);
                terminalRunDurable = true;
                PlanSubmissionResult committed = handler.commit(context);
                if (committed.outcome() == PlanSubmissionResult.Outcome.SUBMITTED) {
                    emit(sink, List.of(components.eventFactory().answer(context, message)));
                    emit(sink, List.of(components.eventFactory().done(
                            context, AgentStopReason.PLAN_SUBMITTED)));
                    sink.complete();
                    return;
                }
                result = committed;
            } catch (Exception e) {
                if (!terminalRunDurable) {
                    try {
                        handler.abort(context);
                    } catch (Exception ignored) {
                        // Preserve the terminal Plan Conflict below.
                    }
                }
                result = PlanSubmissionResult.conflict(
                        "Plan Conflict: terminal persistence failed: " + e.getMessage());
            }
        }

        PlanSubmissionResult safeResult = result == null
                ? PlanSubmissionResult.conflict("Plan Conflict: empty runtime result") : result;
        AgentStopReason reason = safeResult.outcome() == PlanSubmissionResult.Outcome.CONFLICT
                ? AgentStopReason.PLAN_CONFLICT : AgentStopReason.PLAN_SUBMISSION_REJECTED;
        String code = reason == AgentStopReason.PLAN_CONFLICT
                ? "plan_conflict" : "plan_submission_rejected";
        String message = safeResult.message();
        context.runtime().fail(reason, code, message);
        context.setFinalAnswer(message);
        updateTaskSummary(context, message);
        emit(sink, List.of(components.eventFactory().agentError(context)));
        emit(sink, List.of(components.eventFactory().done(context, reason)));
        components.nodeLifecycle().recordStop(context);
        lifecycle.failed(context);
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
