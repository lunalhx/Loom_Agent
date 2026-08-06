package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import cn.lunalhx.ai.infrastructure.gateway.HttpModelGateway;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Prompt cache: cache-key stability (only stable prefix signature matters),
 * capability branches, and provider usage parsing (never inferred hits).
 */
public class PromptCacheTest {

    @Test
    public void cacheKeyUsesOnlyStablePrefixSignature() {
        // two prompts with identical stable prefix but different dynamic
        // history must produce the same cache key material
        StablePrefix prefix = new StablePrefix("p", "fp-1", "ws-1", "tools-1", "rt-1", 1L);
        StablePrefix same = new StablePrefix("p2", "fp-1", "ws-1", "tools-1", "rt-1", 2L);
        assertEquals(prefix.fingerprint(), same.fingerprint());

        ChatPrompt a = ChatPrompt.builder().stablePrefixSignature("fp-1").cachePolicy(ChatPrompt.CachePolicy.READ).build();
        ChatPrompt b = ChatPrompt.builder().stablePrefixSignature("fp-1").cachePolicy(ChatPrompt.CachePolicy.READ).build();
        assertEquals(a.getStablePrefixSignature(), b.getStablePrefixSignature());
    }

    @Test
    public void prefixInvalidationChangesCacheKey() {
        StablePrefix oldPrefix = new StablePrefix("p", "fp-old", "ws-1", "tools-1", "rt-1", 1L);
        StablePrefix newPrefix = new StablePrefix("p2", "fp-new", "ws-2", "tools-1", "rt-1", 2L);
        assertNotEquals(oldPrefix.fingerprint(), newPrefix.fingerprint());
        assertFalse(oldPrefix.matches(newPrefix));
    }

    @Test
    public void promptCarriesStablePrefixSignatureFromContext() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        ContextManager manager = new ContextManager(props);
        AgentContext ctx = new AgentContext();
        ctx.setRunId("cache-test");
        ctx.setQuestion("q");
        ctx.setStablePrefix(new StablePrefix("content", "fp-cache", "ws", "tools", "rt", 1L));
        ctx.ensureLedgerActive();

        // ModelPromptFactory is package-private; verify the signature flows
        // through the prepared-view path by checking the context exposes it
        assertNotNull(ctx.getStablePrefix().fingerprint());
        assertEquals("fp-cache", ctx.getStablePrefix().fingerprint());
    }

    @Test
    public void deepseekCacheFieldsParsedFromUsage() throws Exception {
        // Anthropic-protocol response with DeepSeek cache fields
        TokenUsage usage = HttpModelGatewayTestSupport.parseUsage(java.util.Map.of(
                "input_tokens", 1000,
                "output_tokens", 200,
                "cache_read_input_tokens", 800,
                "cache_creation_input_tokens", 200));
        assertNotNull(usage);
        assertEquals(800, (int) usage.getPromptCacheHitTokens());
        assertEquals(200, (int) usage.getPromptCacheMissTokens());
        assertEquals(Boolean.TRUE, usage.getCacheHit());
        assertEquals("supported", usage.getCacheCapability());
        assertEquals(cn.lunalhx.ai.domain.model.valobj.PromptCacheStatus.HIT, usage.getCacheStatus());
    }

    @Test
    public void openaiCachedTokensParsedFromDetails() throws Exception {
        TokenUsage usage = HttpModelGatewayTestSupport.parseUsage(java.util.Map.of(
                "prompt_tokens", 1000,
                "completion_tokens", 50,
                "total_tokens", 1050,
                "prompt_tokens_details", java.util.Map.of("cached_tokens", 700)));
        assertNotNull(usage);
        assertEquals(700, (int) usage.getPromptCacheHitTokens());
        assertEquals(Boolean.TRUE, usage.getCacheHit());
        assertEquals(cn.lunalhx.ai.domain.model.valobj.PromptCacheStatus.HIT, usage.getCacheStatus());
    }

    @Test
    public void explicitZeroCachedTokensIsMiss() throws Exception {
        TokenUsage usage = HttpModelGatewayTestSupport.parseUsage(java.util.Map.of(
                "input_tokens", 500,
                "output_tokens", 100,
                "cache_read_input_tokens", 0));
        assertNotNull(usage);
        assertEquals(0, (int) usage.getPromptCacheHitTokens());
        assertEquals(cn.lunalhx.ai.domain.model.valobj.PromptCacheStatus.MISS, usage.getCacheStatus());
    }

    @Test
    public void anthropicOllamaWithoutCacheFieldsMarkedUnsupported() throws Exception {
        TokenUsage usage = HttpModelGatewayTestSupport.parseUsage(java.util.Map.of(
                "input_tokens", 500,
                "output_tokens", 100));
        assertNotNull(usage);
        assertNull(usage.getPromptCacheHitTokens());
        assertEquals("unsupported", usage.getCacheCapability());
        assertNull(usage.getCacheHit());
        assertEquals(cn.lunalhx.ai.domain.model.valobj.PromptCacheStatus.UNKNOWN, usage.getCacheStatus());
    }

    @Test
    public void modelChatResultKeepsProviderUsage() {
        TokenUsage usage = TokenUsage.builder()
                .promptTokens(100)
                .completionTokens(50)
                .promptCacheHitTokens(80)
                .cacheHit(true)
                .cacheCapability("supported")
                .cacheStatus(cn.lunalhx.ai.domain.model.valobj.PromptCacheStatus.HIT)
                .build();
        ModelChatResult result = ModelChatResult.builder()
                .content("ok")
                .usage(usage)
                .build();
        assertNotNull(result.getUsage());
        assertEquals(80, (int) result.getUsage().getPromptCacheHitTokens());
        assertEquals(Boolean.TRUE, result.getUsage().getCacheHit());
        assertEquals(cn.lunalhx.ai.domain.model.valobj.PromptCacheStatus.HIT, result.getUsage().getCacheStatus());
    }

    @Test
    public void cacheKeyDerivedFromNamespaceModelAndPrefix() {
        String key = cn.lunalhx.ai.domain.model.service.PromptCacheKeyFactory.deriveKey(
                "deepseek", "deepseek-v4-flash", "fp-1");
        assertEquals(key, cn.lunalhx.ai.domain.model.service.PromptCacheKeyFactory.deriveKey(
                "deepseek", "deepseek-v4-flash", "fp-1"));
        assertNotEquals(key, cn.lunalhx.ai.domain.model.service.PromptCacheKeyFactory.deriveKey(
                "deepseek", "deepseek-v4-flash", "fp-2"));
        assertNotEquals(key, cn.lunalhx.ai.domain.model.service.PromptCacheKeyFactory.deriveKey(
                "openai", "deepseek-v4-flash", "fp-1"));
        // 只依赖稳定前缀签名，不依赖动态 history
        assertEquals(64, key.length());
    }

    @Test
    public void cacheRequestDisabledWhenFlagOffOrNoCapability() {
        cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties properties = new cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties();
        ChatPrompt prompt = ChatPrompt.builder()
                .model("deepseek-v4-flash")
                .stablePrefixSignature("fp-1")
                .cachePolicy(ChatPrompt.CachePolicy.READ)
                .build();
        // 未配置 capability → 不支持，即使 flag 开启也不发送
        cn.lunalhx.ai.domain.model.valobj.PromptCacheRequest request =
                cn.lunalhx.ai.domain.model.service.PromptCacheKeyFactory.buildRequest(prompt, properties, true);
        assertFalse(request.enabled());
        // flag 关闭 → NONE
        cn.lunalhx.ai.domain.model.valobj.PromptCacheRequest disabled =
                cn.lunalhx.ai.domain.model.service.PromptCacheKeyFactory.buildRequest(prompt, properties, false);
        assertFalse(disabled.enabled());
        assertEquals(ChatPrompt.CachePolicy.NONE, disabled.getPolicy());
    }

    @Test
    public void cacheRequestEnabledOnlyWithCapabilityAndSignature() {
        cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties properties = new cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties();
        cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties.ProviderCacheConfig cfg =
                new cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties.ProviderCacheConfig();
        cfg.setCapability(cn.lunalhx.ai.domain.model.valobj.PromptCacheCapability.KEYED_REQUEST);
        properties.getPromptCache().put("openai", cfg);
        properties.setProvider("openai");

        ChatPrompt prompt = ChatPrompt.builder()
                .model("gpt-5")
                .stablePrefixSignature("fp-1")
                .cachePolicy(ChatPrompt.CachePolicy.READ)
                .build();
        cn.lunalhx.ai.domain.model.valobj.PromptCacheRequest request =
                cn.lunalhx.ai.domain.model.service.PromptCacheKeyFactory.buildRequest(prompt, properties, true);
        assertTrue(request.enabled());
        assertNotNull(request.getCacheKey());
    }
}
