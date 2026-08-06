package cn.lunalhx.ai.domain.model.service;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.valobj.PromptCacheCapability;
import cn.lunalhx.ai.domain.model.valobj.PromptCacheRequest;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import org.apache.commons.codec.digest.DigestUtils;

/**
 * 由稳定前缀派生 prompt-cache 请求契约。
 *
 * <p>缓存键 = SHA-256(provider namespace + model family + stable prefix
 * canonical content)。runId、用户输入、会话 ID、动态记忆与 history 永不进入键。
 */
public final class PromptCacheKeyFactory {

    private PromptCacheKeyFactory() {
    }

    public static PromptCacheRequest buildRequest(ChatPrompt prompt,
                                                  ModelRuntimeProperties properties,
                                                  boolean featureEnabled) {
        if (prompt == null) {
            return PromptCacheRequest.none(PromptCacheCapability.UNSUPPORTED);
        }
        PromptCacheCapability capability = prompt.getPromptCacheCapability();
        if (capability == null) {
            capability = PromptCacheCapability.fromProviderModel(
                    properties == null ? null : properties.getProvider(),
                    prompt.getModel(), properties);
        }
        ChatPrompt.CachePolicy policy = prompt.getCachePolicy() == null
                ? ChatPrompt.CachePolicy.NONE : prompt.getCachePolicy();
        if (!featureEnabled || prompt.getStablePrefixSignature() == null) {
            return PromptCacheRequest.none(capability);
        }
        String cacheKey = deriveKey(
                properties == null ? "unknown" : properties.getProvider(),
                prompt.getModel(), prompt.getStablePrefixSignature());
        return request(prompt, policy, capability, cacheKey);
    }

    /** Provider namespace 由调用方显式给定（gateway 场景，避免 null properties 丢失命名空间）。 */
    public static PromptCacheRequest buildRequestWithProvider(ChatPrompt prompt,
                                                              String providerNamespace,
                                                              boolean featureEnabled) {
        if (prompt == null) {
            return PromptCacheRequest.none(PromptCacheCapability.UNSUPPORTED);
        }
        PromptCacheCapability capability = prompt.getPromptCacheCapability();
        if (capability == null) {
            capability = PromptCacheCapability.UNSUPPORTED;
        }
        ChatPrompt.CachePolicy policy = prompt.getCachePolicy() == null
                ? ChatPrompt.CachePolicy.NONE : prompt.getCachePolicy();
        if (!featureEnabled || prompt.getStablePrefixSignature() == null) {
            return PromptCacheRequest.none(capability);
        }
        String cacheKey = deriveKey(
                providerNamespace == null ? "unknown" : providerNamespace,
                prompt.getModel(), prompt.getStablePrefixSignature());
        return request(prompt, policy, capability, cacheKey);
    }

    private static PromptCacheRequest request(ChatPrompt prompt, ChatPrompt.CachePolicy policy,
                                              PromptCacheCapability capability, String cacheKey) {
        return PromptCacheRequest.builder()
                .stablePrefixSignature(prompt.getStablePrefixSignature())
                .cacheKey(cacheKey)
                .policy(policy)
                .retention(prompt.getPromptCacheRetention())
                .capability(capability)
                .build();
    }

    public static String deriveKey(String provider, String model, String stablePrefixFingerprint) {
        String namespace = provider == null ? "unknown" : provider;
        String family = model == null ? "unknown" : model;
        return DigestUtils.sha256Hex(namespace + "\n" + family + "\n" + stablePrefixFingerprint);
    }

}
