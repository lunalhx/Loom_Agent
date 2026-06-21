package cn.lunalhx.ai.domain.agent.flow.hook;

import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;

import java.util.List;

public interface AgentHook {

    List<AgentEvent> onEvent(AgentHookEvent event, AgentHookContext context);

}
