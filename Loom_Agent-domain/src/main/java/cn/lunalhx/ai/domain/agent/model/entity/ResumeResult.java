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
 * mismatch, schema incompatibility, and missing checkpoints.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeResult {

    public enum Kind {
        /** History, working memory and the semantic checkpoint are all valid. */
        FULL_RESTORE,
        /** Some key files changed; their summaries are discarded, history/working memory kept. */
        PARTIAL_RESUME,
        /** Workspace mismatch or no usable checkpoint; only history is restored. */
        WORKSPACE_MISMATCH,
        /** Session schema is incompatible; the session is rejected with a clear error. */
        SCHEMA_INCOMPATIBLE,
        /** No checkpoint at all. */
        NO_CHECKPOINT
    }

    private Kind kind;
    private AgentSession session;
    private TaskCheckpoint checkpoint;
    private WorkingContextMemory workingMemory;
    private List<String> invalidatedKeyFiles;
    private String message;
}
