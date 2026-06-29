package cn.lunalhx.ai.infrastructure.metrics;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;

public class MicrometerAgentMetrics implements AgentMetrics {

    private final MeterRegistry meterRegistry;

    public MicrometerAgentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordRun(String runKind, String status, String errorCode) {
        meterRegistry.counter("loom_agent_run_total",
                        "runKind", safe(runKind),
                        "status", safe(status),
                        "error_code", safe(errorCode))
                .increment();
    }

    @Override
    public void recordNodeDuration(String node, String status, long durationMs) {
        meterRegistry.timer("loom_agent_node_duration_seconds",
                        "node", safe(node),
                        "status", safe(status))
                .record(Duration.ofMillis(Math.max(0L, durationMs)));
    }

    @Override
    public void recordPromptInjectionDetected(String toolName, int matchCount) {
        meterRegistry.counter("loom_agent_prompt_injection_detected_total",
                        "tool", safe(toolName))
                .increment(Math.max(1, matchCount));
    }

    @Override
    public void recordMcpServerInit(String server, String transport, String status) {
        meterRegistry.counter("loom_mcp_server_initialization_total",
                        "server", safe(server),
                        "transport", safe(transport),
                        "status", safe(status))
                .increment();
    }

    @Override
    public void recordMcpToolCall(String server, String tool, String status) {
        meterRegistry.counter("loom_mcp_tool_calls_total",
                        "server", safe(server),
                        "tool", safe(tool),
                        "status", safe(status))
                .increment();
    }

    @Override
    public void recordMcpToolDuration(String server, String tool, String status, long durationMs) {
        meterRegistry.timer("loom_mcp_tool_duration_seconds",
                        "server", safe(server),
                        "tool", safe(tool),
                        "status", safe(status))
                .record(Duration.ofMillis(Math.max(0L, durationMs)));
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

}
