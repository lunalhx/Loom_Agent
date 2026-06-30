package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.Instant;

@Data
public class AgentTraceEventPO {

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
    private String tokenUsageJson;
    private String costJson;
    private String metadataJson;
    private Boolean replayable;
    private Boolean sensitiveRedacted;
    private Instant createTime;

}
