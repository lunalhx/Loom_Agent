package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.model.entity.DelegateRequest;
import cn.lunalhx.ai.domain.agent.model.entity.DelegateResult;

/**
 * Delegate capability for the loom-code {@code delegate} tool: spawn a bounded
 * read-only child agent as a real child run with proper lineage and return a
 * structured safe result. The child never touches the parent session directly.
 */
public interface DelegateRunner {

    DelegateResult delegate(DelegateRequest request);
}
