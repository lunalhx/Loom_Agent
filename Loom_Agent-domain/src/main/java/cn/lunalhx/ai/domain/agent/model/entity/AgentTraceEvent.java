package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTraceEvent {

    private Long id;
    private String traceId;
    private String rootRunId;
    private String runId;
    private String parentRunId;
    private String spanId;
    private String parentSpanId;
    private Long sequenceNo;
    private String eventType;
    private String node;
    private String status;
    private Long durationMs;
    private String summary;
    private String errorCode;
    private String errorMessage;
    private TokenUsage tokenUsage;
    private TraceCost cost;
    private Map<String, Object> metadata;
    private Boolean replayable;
    private Boolean sensitiveRedacted;
    private Instant createdAt;

}
