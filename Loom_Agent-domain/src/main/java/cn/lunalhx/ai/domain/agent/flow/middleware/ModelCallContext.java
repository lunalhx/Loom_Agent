package cn.lunalhx.ai.domain.agent.flow.middleware;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.service.context.PreparedContextView;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ModelCallContext {

    private AgentContext agentContext;
    private String requestModel;
    private Integer maxTokens;
    private Long deadlineEpochMs;
    private Integer escalatedMaxTokens;
    private List<AgentEvent> events;
    private PreparedContextView preparedView;

    public ModelCallContext() {
        this.events = new ArrayList<>();
    }

    public static ModelCallContext of(AgentContext ctx, String model, Integer maxTokens, Long deadline) {
        ModelCallContext mcc = new ModelCallContext();
        mcc.agentContext = ctx;
        mcc.requestModel = model;
        mcc.maxTokens = maxTokens;
        mcc.deadlineEpochMs = deadline;
        return mcc;
    }
}
