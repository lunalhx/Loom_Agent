package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationDeletionResponse {
    private String conversationId;
    private String status;
    private String requestedAt;
    private String completedAt;
    private int retryCount;
    private String lastError;
}
