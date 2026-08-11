package cn.lunalhx.ai.domain.tool.model;

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
    private Boolean approvalRequired;
    private List<String> affectedPaths;
    private Boolean workspaceChanged;
    private String workspaceFingerprint;
    private List<String> diffSummary;
    private Map<String, Object> details;

    /** Adapter metadata used before sanitization/clipping; never persisted. */
    @JsonIgnore
    private transient ToolEvidenceCandidate evidenceCandidate;

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
        evidenceCandidate = null;
    }
}
