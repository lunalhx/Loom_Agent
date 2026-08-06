package cn.lunalhx.ai.domain.model.valobj;

/**
 * Provider prompt-cache 协议能力。
 *
 * <p>{@code UNSUPPORTED} 不发送任何缓存字段；{@code KEYED_REQUEST} 表示可在请求
 * 中携带显式 cache key（如 OpenAI-compatible 的 {@code prompt_cache_key}）；
 * {@code MESSAGE_BLOCK} 表示通过 message content-block cache-control 表达
 * （Anthropic 路径）。未知或不支持 provider 走普通请求。
 */
public enum PromptCacheCapability {

    UNSUPPORTED,
    KEYED_REQUEST,
    MESSAGE_BLOCK;

    public boolean supportsCache() {
        return this != UNSUPPORTED;
    }

    /** 显式 provider/model 配置，禁止从 baseUrl 子串推断。 */
    public static PromptCacheCapability fromProviderModel(String provider, String model,
                                                          ModelRuntimeProperties properties) {
        if (properties == null) {
            return UNSUPPORTED;
        }
        PromptCacheCapability configured = properties.promptCacheCapability(provider, model);
        return configured == null ? UNSUPPORTED : configured;
    }

}
