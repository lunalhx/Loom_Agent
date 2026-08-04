package cn.lunalhx.ai.infrastructure.adapter.deletion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InMemoryConversationPurgeHandler implements ConversationPurgeHandler {

    private static final Logger log = LoggerFactory.getLogger(InMemoryConversationPurgeHandler.class);

    @Override
    public void purge(String conversationId) {
        log.info("InMemory purge completed for conversation {}", conversationId);
    }
}
