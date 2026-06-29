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

    private String safe(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

}
