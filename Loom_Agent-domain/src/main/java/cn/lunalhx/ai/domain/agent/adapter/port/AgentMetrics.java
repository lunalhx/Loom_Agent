package cn.lunalhx.ai.domain.agent.adapter.port;

public interface AgentMetrics {

    void recordRun(String runKind, String status, String errorCode);

    void recordNodeDuration(String node, String status, long durationMs);

    void recordPromptInjectionDetected(String toolName, int matchCount);

    default void recordMcpServerInit(String server, String transport, String status) {
    }

    default void recordMcpToolCall(String server, String tool, String status) {
    }

    default void recordMcpToolDuration(String server, String tool, String status, long durationMs) {
    }

}
