package cn.lunalhx.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentReplayTimeline {

    public static final String MODE = "DRY_REPLAY";

    private String mode;
    private String traceId;
    private String rootRunId;
    private String runId;
    private Boolean includeChildren;
    private List<AgentTraceEvent> events;
    private Boolean costGenerated;

}
