package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;

import java.util.Optional;

/**
 * File-backed session store (production) / in-memory store (unit tests).
 * The CLI must not maintain a second session state machine.
 */
public interface AgentSessionRepository {

    AgentSession save(AgentSession session);

    Optional<AgentSession> find(String sessionId);

    /**
     * Most recently modified valid session for the workspace, or empty.
     * Corrupted sessions are skipped, never overwritten.
     */
    Optional<AgentSession> findLatest(String workspaceRoot);

    void delete(String sessionId);
}
