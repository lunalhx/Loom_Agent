package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.Instant;

@Data
public class AgentRunCheckpointPO {

    private Long id;
    private String runId;
    private Long version;
    private String currentNode;
    private String contextJson;
    private String planJson;
    private String lastToolExecutionJson;
    private String reason;
    private Instant createTime;

}
