package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.state.AgentIdentity;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunKind;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AgentNodeLifecycle {

    private final TraceRecorder traceRecorder;
    private final AgentMetrics agentMetrics;
    private final AgentEventFactory eventFactory;
    private final Map<String, AgentNode> nodes;

    public AgentNodeLifecycle(TraceRecorder traceRecorder,
                              AgentMetrics agentMetrics,
                              AgentEventFactory eventFactory,
                              Map<String, AgentNode> nodes) {
        this.traceRecorder = traceRecorder;
        this.agentMetrics = agentMetrics;
        this.eventFactory = eventFactory;
        this.nodes = nodes;
    }

    public AgentNodeExecution execute(AgentContext context, AgentNode node, Consumer<List<AgentEvent>> emitter) {        String parentSpanId = context.trace().currentSpanId();
        String spanId = traceRecorder.recordNodeStart(context, node, parentSpanId);
        context.trace().setParentSpanId(parentSpanId);
        context.trace().setCurrentSpanId(spanId);
        long startedAt = System.currentTimeMillis();
        putNodeMdc(context, node.name());

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
        String nextNode = result.getNextNode() != null ? result.getNextNode() : node.name();
        long durationMs = System.currentTimeMillis() - startedAt;
        String status = nodeStatus(context, result);
        traceRecorder.recordNodeEnd(context, node, spanId, status, durationMs, "nextNode=" + nextNode, null);
        agentMetrics.recordNodeDuration(node.name(), status, durationMs);
        MDC.clear();

        if (!result.isTerminal()) {
            emitter.accept(result.getEvents());
        }
        return new AgentNodeExecution(result, nextNode);
    }

    public TraceRecorder traceRecorder() {
        return traceRecorder;
    }

    public void cancelled(AgentContext context, Consumer<List<AgentEvent>> emitter) {
        context.runtime().cancel();
        traceRecorder.recordStop(context, "cancelled", "user_cancelled");
        agentMetrics.recordRun(runKind(context), "cancelled", context.runtime().errorCode());
        MDC.clear();
    }

    public void recordStop(AgentContext context) {
        traceRecorder.recordStop(context, stopStatus(context), stopSummary(context));
        agentMetrics.recordRun(runKind(context), stopStatus(context), context.runtime().errorCode());
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
        if (context.getStopReason() == AgentStopReason.PLAN_DEVIATION) {
            return "stopped";
        }
        if (context.modelCall().contextOverflowStage() == cn.lunalhx.ai.domain.agent.model.valobj.ContextOverflowStage.WAITING_USER_INPUT) {
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

    private void putNodeMdc(AgentContext context, String node) {
        AgentIdentity id = context.identity();
        MDC.put("trace_id", StringUtils.defaultString(context.trace().traceId()));
        MDC.put("run_id", StringUtils.defaultString(id.runId()));
        MDC.put("request_id", StringUtils.defaultString(id.requestId()));
        MDC.put("conversation_id", StringUtils.defaultString(id.conversationId()));
        MDC.put("node", StringUtils.defaultString(node));
    }
}
