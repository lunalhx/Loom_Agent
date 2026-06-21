package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRun {

    private String runId;
    private String requestId;
    private String conversationId;
    private String question;
    private String workspace;
    private AgentRunStatus status;
    private String currentNode;
    private Integer step;
    private Long checkpointVersion;
    private Instant createdAt;
    private Instant updatedAt;

}
