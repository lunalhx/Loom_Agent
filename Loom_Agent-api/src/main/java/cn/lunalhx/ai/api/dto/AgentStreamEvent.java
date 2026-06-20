package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStreamEvent {

    private String type;
    private String requestId;
    private String conversationId;
    private Integer step;
    private String node;
    private String thought;
    private String tool;
    private Map<String, Object> input;
    private String observation;
    private Boolean truncated;
    private String answer;
    private String stopReason;
    private Integer stepCount;
    private String code;
    private String message;

}
