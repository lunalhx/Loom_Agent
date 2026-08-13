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
}
