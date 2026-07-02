package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import cn.lunalhx.ai.infrastructure.gateway.DeepSeekModelGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * DeepSeekModelGateway.parseUsage 端到端透传 cache usage 字段的解析测试。
 *
 * <p>该方法同时被 complete 和 SSE 两条链路调用（parseChatResult 与 parseChunk），
 * 同一段解析语义必须满足：
 * <ul>
 *   <li>字段完整：DeepSeek 返回的 prompt_cache_hit_tokens / prompt_cache_miss_tokens 必须如实映射到 TokenUsage；</li>
 *   <li>字段缺失：provider 未返回这两个字段时必须保持 null，不能误解析为 0；</li>
 *   <li>字段为 0：provider 显式返回 0 时必须如实映射为 0（与 null 区分），以支持无 cache 命中的真实业务场景。</li>
 * </ul>
 */
public class DeepSeekModelGatewayUsageTest {

    @Test
    public void parseUsageShouldMapAllFieldsWhenProviderReturnsFullUsage() throws Exception {
        TokenUsage usage = invokeParseUsage("{\n"
                + "  \"prompt_tokens\": 100,\n"
                + "  \"completion_tokens\": 50,\n"
                + "  \"total_tokens\": 150,\n"
                + "  \"prompt_cache_hit_tokens\": 80,\n"
                + "  \"prompt_cache_miss_tokens\": 20\n"
                + "}");

        assertNotNull(usage);
        assertEquals(Integer.valueOf(100), usage.getPromptTokens());
        assertEquals(Integer.valueOf(50), usage.getCompletionTokens());
        assertEquals(Integer.valueOf(150), usage.getTotalTokens());
        assertEquals(Integer.valueOf(80), usage.getPromptCacheHitTokens());
        assertEquals(Integer.valueOf(20), usage.getPromptCacheMissTokens());
    }

    @Test
    public void parseUsageShouldKeepCacheFieldsNullWhenProviderOmitsThem() throws Exception {
        // provider 不返回 cache 字段（其它 provider 或旧版本 DeepSeek）—— 不能被错误解析成 0，
        // 因为 0 在业务上有"无 cache 命中"的含义，null 才是"provider 未声明"的语义。
        TokenUsage usage = invokeParseUsage("{\n"
                + "  \"prompt_tokens\": 42,\n"
                + "  \"completion_tokens\": 7,\n"
                + "  \"total_tokens\": 49\n"
                + "}");

        assertNotNull(usage);
        assertEquals(Integer.valueOf(42), usage.getPromptTokens());
        assertEquals(Integer.valueOf(7), usage.getCompletionTokens());
        assertEquals(Integer.valueOf(49), usage.getTotalTokens());
        assertNull("字段缺失时 promptCacheHitTokens 必须为 null，不能被解析为 0",
                usage.getPromptCacheHitTokens());
        assertNull("字段缺失时 promptCacheMissTokens 必须为 null，不能被解析为 0",
                usage.getPromptCacheMissTokens());
    }

    @Test
    public void parseUsageShouldPreserveZeroCacheValuesWhenProviderReportsZero() throws Exception {
        // provider 显式返回 0（DeepSeek 在无 cache 命中时返回 0 而非省略）—— 必须如实映射为 0，
        // 与"字段缺失"区分开；这是 promptCacheHitTokens=0 唯一合法的业务场景。
        TokenUsage usage = invokeParseUsage("{\n"
                + "  \"prompt_tokens\": 30,\n"
                + "  \"completion_tokens\": 10,\n"
                + "  \"total_tokens\": 40,\n"
                + "  \"prompt_cache_hit_tokens\": 0,\n"
                + "  \"prompt_cache_miss_tokens\": 30\n"
                + "}");

        assertNotNull(usage);
        assertEquals(Integer.valueOf(30), usage.getPromptTokens());
        assertEquals(Integer.valueOf(10), usage.getCompletionTokens());
        assertEquals(Integer.valueOf(40), usage.getTotalTokens());
        assertEquals(Integer.valueOf(0), usage.getPromptCacheHitTokens());
        assertEquals(Integer.valueOf(30), usage.getPromptCacheMissTokens());
    }

    private TokenUsage invokeParseUsage(String usageJson) throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 2, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            DeepSeekModelGateway gateway = new DeepSeekModelGateway(
                    new MockEnvironment(), objectMapper, new ModelRuntimeProperties(), executor);
            Method method = DeepSeekModelGateway.class.getDeclaredMethod(
                    "parseUsage", JsonNode.class);
            method.setAccessible(true);
            JsonNode usageNode = objectMapper.readTree(usageJson);
            return (TokenUsage) method.invoke(gateway, usageNode);
        } finally {
            executor.shutdownNow();
        }
    }
}
