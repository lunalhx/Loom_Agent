package cn.lunalhx.ai.domain.tool.model;

import cn.lunalhx.ai.domain.agent.model.entity.DelegateResult;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResult {

    private boolean success;
    private String observation;
    private boolean truncated;
    private String errorCode;
    private String message;
    private long elapsedMs;

    // ---- loom-code authoritative metadata ----
    private String toolStatus;
    private String toolErrorCode;
    private String securityEventType;
    private Boolean readOnly;
    private EffectProfile effectProfile;
    private List<String> affectedPaths;
    private Boolean workspaceChanged;
    private String workspaceFingerprint;
    private List<String> diffSummary;
    private Map<String, Object> details;
    private DelegateResult delegateResult;

    /** Adapter metadata used before sanitization/clipping; never persisted. */
    @JsonIgnore
    private transient List<ToolEvidenceCandidate> evidenceCandidates;

    /** Process outcome used by the executor before the observation is rendered or clipped. */
    @JsonIgnore
    private transient ShellExecutionResult shellExecutionResult;

    public static ToolResult success(String observation, boolean truncated, long elapsedMs) {
        return ToolResult.builder()
                .success(true)
                .observation(observation)
                .truncated(truncated)
                .elapsedMs(elapsedMs)
                .toolStatus("ok")
                .build();
    }

    public static ToolResult failure(String errorCode, String message, long elapsedMs) {
        return ToolResult.builder()
                .success(false)
                .errorCode(errorCode)
                .message(message)
                .observation("tool_error: " + errorCode + " - " + message)
                .elapsedMs(elapsedMs)
                .toolStatus("error")
                .toolErrorCode(errorCode)
                .build();
    }

    public List<String> affectedPathsSafe() {
        return affectedPaths == null ? List.of() : affectedPaths;
    }

    public void clearTransientEvidence() {
        evidenceCandidates = null;
    }

    public List<ToolEvidenceCandidate> evidenceCandidatesSafe() {
        return evidenceCandidates == null ? List.of() : List.copyOf(evidenceCandidates);
    }
}
