package cn.lunalhx.ai.infrastructure.gateway.diagnostics;

import java.util.Objects;

/**
 * Prompt 缓存诊断的「比较键」。
 *
 * <p>同一键对应同一条比较序列（previous → current）。
 *
 * <p>键维度选择的原因：
 * <ul>
 *   <li>{@code model}：fallback 切换会换模型；不同模型必须使用独立序列以避免 fallback 后的
 *       第一次请求被误判为「与主模型同 payload」；</li>
 *   <li>{@code capability}：{@code stream.chat} 和 {@code complete.agent_decision} 的 payload
 *       结构差异显著；不同 capability 不应共享 baseline；</li>
 *   <li>{@code purpose}：同一 capability 下 background memory extraction 与 foreground chat
 *       的 prompt 差异极大，purpose 必须隔离 baseline；</li>
 *   <li>{@code conversationId}：并发 conversation 之间不能互相覆盖；同 conversation 内的
 *       retry 自然落到同一键，第二次送出的同一 payload 会被识别为 IDENTICAL。</li>
 * </ul>
 *
 * <p>任一字段为 null / blank 时会被规范化为 {@code "none"} 哨兵，避免 NPE 同时保证相等性语义。
 */
public record PromptCacheDiagnosticLineageKey(
        String model,
        String capability,
        String purpose,
        String conversationId
) {

    public PromptCacheDiagnosticLineageKey {
        model = normalize(model);
        capability = normalize(capability);
        purpose = normalize(purpose);
        conversationId = normalize(conversationId);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PromptCacheDiagnosticLineageKey that)) return false;
        return Objects.equals(model, that.model)
                && Objects.equals(capability, that.capability)
                && Objects.equals(purpose, that.purpose)
                && Objects.equals(conversationId, that.conversationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(model, capability, purpose, conversationId);
    }
}
