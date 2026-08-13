package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of a session resume attempt. Explicitly distinguishes the outcome
 * so the caller can react differently to partial invalidation, workspace
 * mismatch, and schema incompatibility.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeResult {

    public enum Kind {
        /** Working memory and Session context are valid. */
        FULL_RESTORE,
        /** Some key files changed; their summaries are discarded, Session kept. */
        PARTIAL_RESUME,
        /** Workspace mismatch; Session is not activated. */
        WORKSPACE_MISMATCH,
        /** Session schema is incompatible; the session is rejected with a clear error. */
        SCHEMA_INCOMPATIBLE
    }

    private Kind kind;
    private AgentSession session;
    private WorkingContextMemory workingMemory;
    private List<String> invalidatedKeyFiles;
    private String message;
}
