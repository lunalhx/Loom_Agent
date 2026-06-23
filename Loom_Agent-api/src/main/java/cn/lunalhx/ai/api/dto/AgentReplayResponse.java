package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentReplayResponse implements Serializable {

    private static final long serialVersionUID = 3567657952178442620L;

    private String mode;
    private String traceId;
    private String rootRunId;
    private String runId;
    private Boolean includeChildren;
    private List<AgentReplayEventDTO> events;
    private Boolean costGenerated;

}
