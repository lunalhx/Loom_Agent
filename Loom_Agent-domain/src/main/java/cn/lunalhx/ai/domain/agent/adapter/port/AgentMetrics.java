package cn.lunalhx.ai.domain.agent.adapter.port;

public interface AgentMetrics {

    void recordRun(String runKind, String status, String errorCode);

    void recordNodeDuration(String node, String status, long durationMs);

    void recordPromptInjectionDetected(String toolName, int matchCount);

}
