package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;

public class NoopAgentMetrics implements AgentMetrics {

    @Override
    public void recordRun(String runKind, String status, String errorCode) {
    }

    @Override
    public void recordNodeDuration(String node, String status, long durationMs) {
    }

}
