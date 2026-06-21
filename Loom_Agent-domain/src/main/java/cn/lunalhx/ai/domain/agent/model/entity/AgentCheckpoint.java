package cn.lunalhx.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCheckpoint {

    private String runId;
    private Long version;
    private String currentNode;
    private AgentContextSnapshot contextSnapshot;
    private AgentPlan plan;
    private String lastToolExecutionJson;
    private String reason;
    private Instant createdAt;

}
