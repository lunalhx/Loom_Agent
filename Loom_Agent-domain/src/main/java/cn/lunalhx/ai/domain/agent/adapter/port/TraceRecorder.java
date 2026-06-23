package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;

import java.util.List;
import java.util.Map;

public interface TraceRecorder {

    String recordNodeStart(AgentContext context, AgentNode node, String parentSpanId);

    void recordNodeEnd(AgentContext context,
                       AgentNode node,
                       String spanId,
                       String status,
                       long durationMs,
                       String summary,
                       Throwable error);

    void recordStop(AgentContext context, String status, String summary);

    void recordModelUsage(AgentContext context,
                          String node,
                          TokenUsage usage,
                          TraceCost cost,
                          Map<String, Object> metadata);

    void recordModelGatewayEvent(AgentContext context,
                                 String eventType,
                                 String node,
                                 String status,
                                 long durationMs,
                                 String summary,
                                 Throwable error,
                                 Map<String, Object> metadata);

    List<AgentTraceEvent> timeline(String runId);

    List<AgentTraceEvent> timelineByTraceId(String traceId);

}
