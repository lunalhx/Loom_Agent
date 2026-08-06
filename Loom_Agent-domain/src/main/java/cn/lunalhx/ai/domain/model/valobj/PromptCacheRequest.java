package cn.lunalhx.ai.domain.model.valobj;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import lombok.Builder;
import lombok.Getter;

/**
 * 一次模型调用的 prompt-cache 请求契约：由 domain 层生成，由 infrastructure
 * 层映射为 provider 协议字段。缓存键只由稳定前缀派生，动态 history / 用户请求
 * 永不进入键。
 */
@Getter
@Builder
public final class PromptCacheRequest {

    /** 稳定前缀签名（fingerprint）。 */
    private final String stablePrefixSignature;

    /** SHA-256 缓存键：provider namespace + model family + stable prefix canonical content。 */
    private final String cacheKey;

    /** 请求策略：NONE / READ / READ_WRITE。 */
    private final ChatPrompt.CachePolicy policy;

    /** retention（可选，如 OpenAI 的 prompt_cache_retention）。 */
    private final String retention;

    /** provider/model 能力快照。 */
    private final PromptCacheCapability capability;

    public boolean enabled() {
        return capability != null && capability.supportsCache() && policy != null && policy != ChatPrompt.CachePolicy.NONE;
    }

    public static PromptCacheRequest none(PromptCacheCapability capability) {
        return PromptCacheRequest.builder()
                .capability(capability == null ? PromptCacheCapability.UNSUPPORTED : capability)
                .policy(ChatPrompt.CachePolicy.NONE)
                .build();
    }

}
