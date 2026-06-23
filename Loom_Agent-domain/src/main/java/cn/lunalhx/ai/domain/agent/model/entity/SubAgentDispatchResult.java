package cn.lunalhx.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubAgentDispatchResult {

    private boolean success;
    private String errorCode;
    private String message;
    private String observation;
    private boolean truncated;
    private long elapsedMs;
    @Builder.Default
    private List<SubAgentResult> results = new ArrayList<>();

    public static SubAgentDispatchResult failure(String errorCode, String message, long elapsedMs) {
        return SubAgentDispatchResult.builder()
                .success(false)
                .errorCode(errorCode)
                .message(message)
                .observation("sub_agent_error: " + errorCode + " - " + message)
                .elapsedMs(elapsedMs)
                .build();
    }

}
