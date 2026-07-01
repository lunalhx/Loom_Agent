package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.Instant;

@Data
public class ConversationDeletionPO {
    private String conversationId;
    private String status;
    private Instant requestedAt;
    private Instant updatedAt;
    private Instant completedAt;
    private int retryCount;
    private String lastError;
    private String lockedBy;
    private String lockExpiresAt;
}
