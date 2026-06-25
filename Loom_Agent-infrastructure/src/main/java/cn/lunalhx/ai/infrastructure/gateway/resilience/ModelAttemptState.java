package cn.lunalhx.ai.infrastructure.gateway.resilience;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;

public record ModelAttemptState(
        ChatPrompt prompt,
        ModelCallKey key,
        int attemptNo,
        int consecutiveOverload,
        String fallbackReason
) {

    public ModelAttemptState {
        if (prompt == null) {
            throw new IllegalArgumentException("prompt must not be null");
        }
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be >= 1");
        }
    }

    public ModelAttemptState next(ChatPrompt prompt, ModelCallKey key, int overloadCount, String fallbackReason) {
        return new ModelAttemptState(prompt, key, this.attemptNo + 1, overloadCount, fallbackReason);
    }

}
