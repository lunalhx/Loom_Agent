package cn.lunalhx.ai.domain.agent.flow.hook;

public interface AgentHook {

    AgentHookResult onEvent(AgentHookEvent event, AgentHookContext context);

}
