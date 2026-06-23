package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTraceEventDTO implements Serializable {

    private static final long serialVersionUID = 5765413868097702205L;

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
    private TokenUsageDTO tokenUsage;
    private Map<String, Object> cost;
    private Map<String, Object> metadata;
    private Boolean replayable;
    private Boolean sensitiveRedacted;
    private String createdAt;

}
