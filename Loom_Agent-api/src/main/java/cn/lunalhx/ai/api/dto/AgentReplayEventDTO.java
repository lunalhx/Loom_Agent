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
public class AgentReplayEventDTO implements Serializable {

    private static final long serialVersionUID = -5268256058643227612L;

    private Long eventId;
    private Long sequenceNo;
    private String eventType;
    private String runId;
    private String parentRunId;
    private String spanId;
    private String parentSpanId;
    private String nodeName;
    private String status;
    private String summary;
    private Long durationMs;
    private String errorCode;
    private String errorMessage;
    private TokenUsageDTO tokenUsage;
    private Map<String, Object> cost;
    private Map<String, Object> metadata;
    private Boolean replayable;
    private Boolean sensitiveRedacted;
    private String createdAt;

}
