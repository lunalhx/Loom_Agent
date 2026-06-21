package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentPlanItemStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentPlanItem {

    private String id;
    private Integer order;
    private String content;
    private AgentPlanItemStatus status;
    private String evidence;
    private String blocker;
    private Instant updateTime;

    public boolean incomplete() {
        return status == null || !status.terminal();
    }

    public Map<String, Object> toView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("order", order);
        view.put("content", content);
        view.put("status", status == null ? AgentPlanItemStatus.PENDING.code() : status.code());
        if (StringUtils.isNotBlank(evidence)) {
            view.put("evidence", evidence);
        }
        if (StringUtils.isNotBlank(blocker)) {
            view.put("blocker", blocker);
        }
        return view;
    }

}
