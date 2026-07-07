package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookAction;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookRegistry;
import cn.lunalhx.ai.domain.agent.flow.hook.StopHookResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.state.AgentIdentity;
import cn.lunalhx.ai.domain.agent.model.state.AgentRuntimeState;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunKind;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentErrorCode;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;

import java.util.List;
import java.util.function.Consumer;

public final class AgentNodeLifecycle {

    private final TraceRecorder traceRecorder;
    private final AgentMetrics agentMetrics;
    private final AgentHookRegistry hookRegistry;
    private final AgentEventFactory eventFactory;

    public AgentNodeLifecycle(TraceRecorder traceRecorder,
                              AgentMetrics agentMetrics,
                              AgentHookRegistry hookRegistry,
                              AgentEventFactory eventFactory) {
        this.traceRecorder = traceRecorder;
        this.agentMetrics = agentMetrics;
        this.hookRegistry = hookRegistry;
        this.eventFactory = eventFactory;
    }

    public void userPromptSubmitted(AgentContext context, Consumer<List<AgentEvent>> emitter) {
        emitter.accept(hookRegistry.trigger(AgentHookEvent.USER_PROMPT_SUBMIT, AgentHookContext.builder()
                .agentContext(context)
                .node(AgentNodeNames.START)
                .reason("user_prompt_submit")
                .build()));
    }

    public AgentNodeExecution execute(AgentContext context, AgentNode node, Consumer<List<AgentEvent>> emitter) {
        String parentSpanId = context.trace().currentSpanId();
        String spanId = traceRecorder.recordNodeStart(context, node, parentSpanId);
        context.trace().setParentSpanId(parentSpanId);
        context.trace().setCurrentSpanId(spanId);
        long startedAt = System.currentTimeMillis();
        putNodeMdc(context, node.name());

        StopHookResult beforeResult = hookRegistry.triggerInterruptible(AgentHookEvent.BEFORE_NODE,
                AgentHookContext.builder()
                        .agentContext(context)
                        .node(node.name())
                        .reason("before_node:" + node.name())
                        .build());
        emitter.accept(beforeResult.events());

        if (beforeResult.continued()) {
            AgentHookAction action = beforeResult.action();
            if (action.isClearTerminalState()) {
                context.runtime().clearOutcomeForContinuation();
                context.approval().setPendingApprovalId(null);
            }
            traceRecorder.recordStop(context, "continued",
                    "before_node_hook_continued to " + action.getNextNode());
            agentMetrics.recordRun(runKind(context), "continued", context.runtime().errorCode());
            MDC.clear();
            return AgentNodeExecution.stopContinued(action.getNextNode(), action);
        }

        emitter.accept(List.of(eventFactory.nodeStarted(context, node)));

        NodeResult result;
        try {
            result = node.apply(context);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startedAt;
            traceRecorder.recordNodeEnd(context, node, spanId, "failed", durationMs, "error=" + e.getMessage(), e);
            agentMetrics.recordNodeDuration(node.name(), "failed", durationMs);
            MDC.clear();
            throw e;
        }

        recordContextCompactedEvents(context, node, startedAt, result.getEvents());
        String nextNode = result.isTerminal() ? node.name() : result.getNextNode();
        long durationMs = System.currentTimeMillis() - startedAt;
        String status = nodeStatus(context, result);
        traceRecorder.recordNodeEnd(context, node, spanId, status, durationMs, "nextNode=" + nextNode, null);
        agentMetrics.recordNodeDuration(node.name(), status, durationMs);
        emitter.accept(hookRegistry.trigger(AgentHookEvent.AFTER_NODE, AgentHookContext.builder()
                .agentContext(context)
                .node(node.name())
                .nextNode(nextNode)
                .reason("after_node:" + node.name())
                .build()));
        MDC.clear();

        if (result.isTerminal()) {
            return AgentNodeExecution.terminalWithDeferred(result, node.name(), result.getEvents());
        }
        emitter.accept(result.getEvents());
        return new AgentNodeExecution(result, nextNode);
    }

    public AgentNodeExecution resolveStop(AgentContext context, AgentNode terminalNode,
                                          List<AgentEvent> terminalEvents,
                                          Consumer<List<AgentEvent>> emitter) {
        StopHookResult stopResult = hookRegistry.triggerStop(AgentHookEvent.STOP, AgentHookContext.builder()
                .agentContext(context)
                .node(terminalNode.name())
                .reason("stop:" + terminalNode.name())
                .build());

        emitter.accept(stopResult.events());

        if (stopResult.continued()) {
            AgentHookAction action = stopResult.action();
            if (action.isClearTerminalState()) {
                context.runtime().clearOutcomeForContinuation();
                context.approval().setPendingApprovalId(null);
            }
            traceRecorder.recordStop(context, "continued", "stop_hook_continued to " + action.getNextNode());
            agentMetrics.recordRun(runKind(context), "continued", context.runtime().errorCode());
            MDC.clear();
            return AgentNodeExecution.stopContinued(action.getNextNode(), action);
        }

        traceRecorder.recordStop(context, stopStatus(context), stopSummary(context));
        agentMetrics.recordRun(runKind(context), stopStatus(context), context.runtime().errorCode());
        emitter.accept(hookRegistry.trigger(AgentHookEvent.AFTER_STOP, AgentHookContext.builder()
                .agentContext(context)
                .node(terminalNode.name())
                .reason("after_stop:" + terminalNode.name())
                .build()));
        emitter.accept(terminalEvents);
        if (AgentNodeNames.APPROVAL_GATE.equals(terminalNode.name())
                && StringUtils.isNotBlank(context.getPendingApprovalId())) {
            emitter.accept(List.of(eventFactory.pausedForApproval(context)));
        }
        MDC.clear();
        return new AgentNodeExecution(NodeResult.terminal(List.of()), terminalNode.name());
    }

    public void cancelled(AgentContext context, Consumer<List<AgentEvent>> emitter) {
        context.runtime().cancel();
        traceRecorder.recordStop(context, "cancelled", "user_cancelled");
        agentMetrics.recordRun(runKind(context), "cancelled", null);
        emitter.accept(hookRegistry.trigger(AgentHookEvent.AFTER_STOP, AgentHookContext.builder()
                .agentContext(context)
                .node(context.runtime().currentNode())
                .reason("user_cancelled")
                .build()));
        MDC.clear();
    }

    private void recordContextCompactedEvents(AgentContext context, AgentNode node, long startedAt, List<AgentEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (AgentEvent event : events) {
            if (event.getType() == AgentEventType.CONTEXT_COMPACTED) {
                traceRecorder.recordModelGatewayEvent(context,
                        AgentEventType.CONTEXT_COMPACTED.eventName(),
                        node.name(),
                        "success",
                        System.currentTimeMillis() - startedAt,
                        event.getMessage(),
                        null,
                        event.getMetadata());
            }
        }
    }

    public static String nodeStatus(AgentContext context, NodeResult result) {
        if (StringUtils.isNotBlank(context.runtime().errorCode())) {
            return "failed";
        }
        if (result != null && result.isTerminal()) {
            return "terminal";
        }
        return "success";
    }

    public static String stopStatus(AgentContext context) {
        if (StringUtils.isNotBlank(context.budget().budgetBlockedReason())) {
            return "budget_exceeded";
        }
        if (StringUtils.isNotBlank(context.runtime().errorCode())) {
            return "failed";
        }
        if (context.recovery().contextRecoveryStage() == ContextRecoveryStage.WAITING_USER_INPUT) {
            return "waiting_user_input";
        }
        return "completed";
    }

    public static String stopSummary(AgentContext context) {
        if (StringUtils.isNotBlank(context.budget().budgetBlockedReason())) {
            return context.budget().budgetBlockedReason();
        }
        if (StringUtils.isNotBlank(context.runtime().finalAnswer())) {
            return StringUtils.abbreviate(context.runtime().finalAnswer(), 500);
        }
        return StringUtils.defaultString(context.runtime().errorMessage());
    }

    public static String runKind(AgentContext context) {
        return StringUtils.isBlank(context.identity().parentRunId()) ? AgentRunKind.ROOT.name() : AgentRunKind.CHILD.name();
    }

    public void persistFailure(AgentContext context, Consumer<List<AgentEvent>> emitter) {
        if (context == null) {
            return;
        }
        context.runtime().fail(AgentStopReason.MODEL_ERROR,
                AgentErrorCode.WORKSPACE_UNDO_BUSY.code(),
                AgentErrorCode.WORKSPACE_UNDO_BUSY.defaultMessage());
        emitter.accept(hookRegistry.trigger(AgentHookEvent.AFTER_STOP, AgentHookContext.builder()
                .agentContext(context)
                .node(AgentNodeNames.START)
                .reason("workspace_undo_busy")
                .build()));
    }

    private void putNodeMdc(AgentContext context, String node) {
        AgentIdentity id = context.identity();
        MDC.put("trace_id", StringUtils.defaultString(context.trace().traceId()));
        MDC.put("run_id", StringUtils.defaultString(id.runId()));
        MDC.put("request_id", StringUtils.defaultString(id.requestId()));
        MDC.put("conversation_id", StringUtils.defaultString(id.conversationId()));
        MDC.put("node", StringUtils.defaultString(node));
    }
}
