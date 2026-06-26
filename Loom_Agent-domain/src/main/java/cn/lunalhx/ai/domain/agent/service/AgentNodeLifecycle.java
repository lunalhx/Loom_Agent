package cn.lunalhx.ai.domain.agent.service;

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
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunKind;
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
        String parentSpanId = context.getCurrentSpanId();
        String spanId = traceRecorder.recordNodeStart(context, node, parentSpanId);
        context.setParentSpanId(parentSpanId);
        context.setCurrentSpanId(spanId);
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
                context.setStopReason(null);
                context.setFinalAnswer(null);
                context.setErrorCode(null);
                context.setErrorMessage(null);
                context.setPendingApprovalId(null);
            }
            traceRecorder.recordStop(context, "continued",
                    "before_node_hook_continued to " + action.getNextNode());
            agentMetrics.recordRun(runKind(context), "continued", context.getErrorCode());
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

    /**
     * Resolve a terminal node: run STOP hooks, and either emit terminal events (proceed)
     * or return a continuation action (intercepted by a stop hook).
     */
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
                context.setStopReason(null);
                context.setFinalAnswer(null);
                context.setErrorCode(null);
                context.setErrorMessage(null);
                context.setPendingApprovalId(null);
            }
            traceRecorder.recordStop(context, "continued", "stop_hook_continued to " + action.getNextNode());
            agentMetrics.recordRun(runKind(context), "continued", context.getErrorCode());
            MDC.clear();
            return AgentNodeExecution.stopContinued(action.getNextNode(), action);
        }

        emitter.accept(terminalEvents);

        traceRecorder.recordStop(context, stopStatus(context), stopSummary(context));
        agentMetrics.recordRun(runKind(context), stopStatus(context), context.getErrorCode());
        emitter.accept(hookRegistry.trigger(AgentHookEvent.AFTER_STOP, AgentHookContext.builder()
                .agentContext(context)
                .node(terminalNode.name())
                .reason("after_stop:" + terminalNode.name())
                .build()));
        MDC.clear();
        return new AgentNodeExecution(NodeResult.terminal(List.of()), terminalNode.name());
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
        if (StringUtils.isNotBlank(context.getErrorCode())) {
            return "failed";
        }
        if (result != null && result.isTerminal()) {
            return "terminal";
        }
        return "success";
    }

    public static String stopStatus(AgentContext context) {
        if (StringUtils.isNotBlank(context.getBudgetBlockedReason())) {
            return "budget_exceeded";
        }
        if (StringUtils.isNotBlank(context.getErrorCode())) {
            return "failed";
        }
        if (context.getContextRecoveryStage() == ContextRecoveryStage.WAITING_USER_INPUT) {
            return "waiting_user_input";
        }
        return "completed";
    }

    public static String stopSummary(AgentContext context) {
        if (StringUtils.isNotBlank(context.getBudgetBlockedReason())) {
            return context.getBudgetBlockedReason();
        }
        if (StringUtils.isNotBlank(context.getFinalAnswer())) {
            return StringUtils.abbreviate(context.getFinalAnswer(), 500);
        }
        return StringUtils.defaultString(context.getErrorMessage());
    }

    public static String runKind(AgentContext context) {
        return StringUtils.isBlank(context.getParentRunId()) ? AgentRunKind.ROOT.name() : AgentRunKind.CHILD.name();
    }

    private void putNodeMdc(AgentContext context, String node) {
        MDC.put("trace_id", StringUtils.defaultString(context.getTraceId()));
        MDC.put("run_id", StringUtils.defaultString(context.getRunId()));
        MDC.put("request_id", StringUtils.defaultString(context.getRequestId()));
        MDC.put("conversation_id", StringUtils.defaultString(context.getConversationId()));
        MDC.put("node", StringUtils.defaultString(node));
    }
}
