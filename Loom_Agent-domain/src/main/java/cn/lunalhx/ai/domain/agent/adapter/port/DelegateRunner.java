package cn.lunalhx.ai.domain.agent.adapter.port;

/**
 * Delegate capability for the loom-code {@code delegate} tool: spawn a bounded
 * read-only child agent as a real child run with proper lineage and return its
 * final result. The child never touches the parent session directly.
 */
public interface DelegateRunner {

    /**
     * @param task         the child task text
     * @param maxSteps     child step budget (max 3)
     * @param parentRunId  the delegating parent run id
     * @param rootRunId    the root run id inherited from the parent chain
     * @param sessionId    the session id inherited from the parent chain
     * @param workspace    the resolved workspace root
     * @param parentSummary recent parent summary (max 300 chars)
     */
    String delegate(String task, int maxSteps, String parentRunId, String rootRunId,
                    String sessionId, String workspace, String parentSummary);
}
