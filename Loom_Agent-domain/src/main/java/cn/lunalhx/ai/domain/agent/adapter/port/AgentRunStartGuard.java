package cn.lunalhx.ai.domain.agent.adapter.port;

/**
 * A transient authorization acquired immediately before a Run is initialized.
 * The returned lease stays held through durable Run initialization.
 */
@FunctionalInterface
public interface AgentRunStartGuard {

    AutoCloseable acquire() throws Exception;
}
