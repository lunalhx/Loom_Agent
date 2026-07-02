package cn.lunalhx.ai.infrastructure.gateway.diagnostics;

import java.util.List;
import java.util.Map;

/**
 * 一次模型请求的诊断上下文，连接 {@link PromptCacheDiagnosticHook#beforeSend} 与
 * {@link PromptCacheDiagnosticHook#afterSend}。
 *
 * <p>状态由 hook 内部产生并在内部消费：仅携带「已经计算好的诊断结果 + 诊断所需的最少输入」
 * （原始 payload 与当前 messages），不持有任何网络 / IO 状态。
 *
 * <p>当 {@link PromptCacheDiagnosticHook} 被禁用时，{@code beforeSend} 返回 null，
 * {@code afterSend} 在收到 null 时直接 no-op。
 */
public final class PromptCacheDiagnosticContext {

    private final PromptCacheDiagnosticLineageKey lineageKey;
    private final CacheDiagnosticResult result;
    private final String rawPayload;
    private final List<Map<String, String>> currentMessages;

    public PromptCacheDiagnosticContext(PromptCacheDiagnosticLineageKey lineageKey,
                                        CacheDiagnosticResult result,
                                        String rawPayload,
                                        List<Map<String, String>> currentMessages) {
        this.lineageKey = lineageKey;
        this.result = result;
        this.rawPayload = rawPayload;
        this.currentMessages = currentMessages;
    }

    public PromptCacheDiagnosticLineageKey lineageKey() {
        return lineageKey;
    }

    public CacheDiagnosticResult result() {
        return result;
    }

    /**
     * 当前请求的完整原始 JSON payload。{@code afterSend} 仅在 {@code logRedactedBody=true}
     * 时才读取；未开启时不应被消费，避免在热路径上做任何额外处理。
     */
    public String rawPayload() {
        return rawPayload;
    }

    public List<Map<String, String>> currentMessages() {
        return currentMessages;
    }
}
