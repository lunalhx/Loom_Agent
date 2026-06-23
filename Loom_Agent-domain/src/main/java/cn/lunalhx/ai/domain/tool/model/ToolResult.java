package cn.lunalhx.ai.domain.tool.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private String artifactId;
    private Integer originalChars;
    private Integer retainedChars;
    private String sha256;

    public static ToolResult success(String observation, boolean truncated, long elapsedMs) {
        return ToolResult.builder()
                .success(true)
                .observation(observation)
                .truncated(truncated)
                .elapsedMs(elapsedMs)
                .build();
    }

    public static ToolResult failure(String errorCode, String message, long elapsedMs) {
        return ToolResult.builder()
                .success(false)
                .errorCode(errorCode)
                .message(message)
                .observation("tool_error: " + errorCode + " - " + message)
                .elapsedMs(elapsedMs)
                .build();
    }

}
