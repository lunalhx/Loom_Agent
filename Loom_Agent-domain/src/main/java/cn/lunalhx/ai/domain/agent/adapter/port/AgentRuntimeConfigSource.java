package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunConfig;

@FunctionalInterface
public interface AgentRuntimeConfigSource {

    AgentRunConfig captureRunConfig();
}
