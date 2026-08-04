package cn.lunalhx.ai.domain.agent.adapter.port;

/**
 * Minimal delegate capability for the loom-code {@code delegate} tool: spawn a
 * bounded read-only child agent and return its final result.
 */
public interface DelegateRunner {

    String delegate(String task, int maxSteps);
}
