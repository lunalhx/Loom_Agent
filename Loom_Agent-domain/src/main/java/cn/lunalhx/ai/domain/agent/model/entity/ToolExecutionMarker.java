package cn.lunalhx.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sanitized execution-window marker for one Tool Call. Stored on
 * AgentCheckpoint before adapter invocation; a marker without a matching
 * durable History result becomes an Interrupted Tool Call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionMarker {

    private String toolCallId;
    private String toolName;
    private String sanitizedInput;
    /** Workspace-relative path for file-mutation reconciliation; never raw content. */
    private String safetyTarget;
    /** SHA-256 of intended file bytes; never the raw payload. */
    private String expectedDigest;
    /** True only when safetyTarget + expectedDigest match current Repository State. */
    private Boolean reconciled;
    /** True after the user Continues with Ambiguity for this unresolved call. */
    private Boolean ambiguityAccepted;

    public boolean awaitsAmbiguityReview() {
        return !Boolean.TRUE.equals(reconciled) && !Boolean.TRUE.equals(ambiguityAccepted);
    }
}
