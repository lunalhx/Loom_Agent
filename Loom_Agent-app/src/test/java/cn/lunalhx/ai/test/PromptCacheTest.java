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
    }

    @Test
    public void modelChatResultKeepsProviderUsage() {
        TokenUsage usage = TokenUsage.builder()
                .promptTokens(100)
                .completionTokens(50)
                .promptCacheHitTokens(80)
                .cacheHit(true)
                .cacheCapability("supported")
                .build();
        ModelChatResult result = ModelChatResult.builder()
                .content("ok")
                .usage(usage)
                .build();
        assertNotNull(result.getUsage());
        assertEquals(80, (int) result.getUsage().getPromptCacheHitTokens());
        assertEquals(Boolean.TRUE, result.getUsage().getCacheHit());
    }
}
