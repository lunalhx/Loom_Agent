package cn.lunalhx.ai.domain.tool.sandbox;

public interface SandboxProvider extends AutoCloseable {

    SandboxLease acquire(SandboxRequest request);

    void endConversation(String conversationId);

    @Override
    void close();
}
