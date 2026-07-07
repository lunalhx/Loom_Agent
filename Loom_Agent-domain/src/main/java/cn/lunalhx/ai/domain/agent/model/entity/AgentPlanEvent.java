package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentPlanItemStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentPlanEvent {

    private int sequence;
    private String type;
    private String itemId;
    private String reason;
    private Instant timestamp;
    private AgentPlanItemStatus beforeStatus;
    private AgentPlanItemStatus afterStatus;
    private Set<String> changedFields;

}
