package cn.lunalhx.ai.domain.tool.sandbox;

public interface SandboxLease extends AutoCloseable {

    Sandbox sandbox();

    @Override
    void close();
}
