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
public class AgentTraceTimelineResponse implements Serializable {

    private static final long serialVersionUID = -4787424146180313698L;

    private String runId;
    private String traceId;
    private String rootRunId;
    private List<AgentTraceEventDTO> events;

}
