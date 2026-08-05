package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunStatusResponse {

    private String runId;
    private String status;
    private String currentNode;
    private Integer toolSteps;
    private Integer modelAttempts;
    private String lastTool;
    private String stopReason;
    private String finalAnswer;
    private Long checkpointVersion;
    private Boolean terminal;
    private Boolean resumable;
    private Instant updatedAt;
}
