package cn.lunalhx.ai.infrastructure.adapter.deletion;

public interface ConversationPurgeHandler {

    void purge(String conversationId) throws Exception;
}
