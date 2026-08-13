package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.model.entity.AttemptLease;

import java.util.Optional;

/**
 * Durable Attempt Lease. Acquire creates a new fenced Attempt; heartbeat keeps
 * the owner healthy; a mismatched or expired fence cannot write.
 */
public interface AttemptLeaseRepository {

    Optional<AttemptLease> find(String runId);

    /**
     * Claim exclusive ownership with a new fence. Fails when a healthy owner
     * still holds the Run.
     */
    Optional<AttemptLease> tryAcquire(String runId, String attemptId);

    boolean heartbeat(String runId, String fence);

    boolean release(String runId, String fence);

    /** True when a non-released lease has a recent heartbeat. */
    boolean isHealthy(String runId);

    /**
     * Rejects a stale, released, or expired fence before a Run write.
     *
     * @throws IllegalStateException if this fence may not write
     */
    void requireWritable(String runId, String fence);

    /**
     * Re-validates this fence under the Run lock and renews the heartbeat.
     *
     * @throws IllegalStateException if this fence may not write
     */
    void requireWritableAndRenew(String runId, String fence);
}
