package cn.lunalhx.ai.domain.agent.model.entity;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDecision {

    private String type;
    private String thought;
    private String tool;
    private JsonNode input;
    private Map<String, Object> inputView;
    private String answer;
    private List<Map<String, Object>> evidence;

}
