package cn.lunalhx.ai.infrastructure.gateway.diagnostics;

import java.util.Objects;

/**
 * 发送给模型的单条消息（role + content），是缓存诊断的最小比较单元。
 * 不可变；role / content 为 null 时按空字符串处理，确保哈希稳定。
 */
public final class CanonicalMessage {

    private final String role;
    private final String content;

    public CanonicalMessage(String role, String content) {
        this.role = role == null ? "" : role;
        this.content = content == null ? "" : content;
    }

    public static CanonicalMessage of(String role, String content) {
        return new CanonicalMessage(role, content);
    }

    public String role() {
        return role;
    }

    public String content() {
        return content;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CanonicalMessage other)) {
            return false;
        }
        return role.equals(other.role) && content.equals(other.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, content);
    }

    /**
     * 仅暴露 role，避免在日志/异常中泄露 content。
     */
    @Override
    public String toString() {
        return "CanonicalMessage{role='" + role + "'}";
    }
}
