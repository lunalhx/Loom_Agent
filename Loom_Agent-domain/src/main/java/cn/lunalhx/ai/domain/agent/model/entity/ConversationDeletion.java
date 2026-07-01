package cn.lunalhx.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationDeletion {
    private String conversationId;
    private String status;
    private Instant requestedAt;
    private Instant updatedAt;
    private Instant completedAt;
    private int retryCount;
    private String lastError;
}
