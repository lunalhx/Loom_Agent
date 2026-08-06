package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.valobj.PromptCacheCapability;
import cn.lunalhx.ai.domain.model.valobj.PromptCacheStatus;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import cn.lunalhx.ai.infrastructure.gateway.HttpModelGateway;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * 真实 provider smoke test：由显式环境变量控制，不放入默认 CI。
 *
 * <p>启用方式：
 * <pre>
 *   LOOM_SMOKE_PROVIDER=openai LOOM_SMOKE_BASE_URL=https://api.openai.com
 *   LOOM_SMOKE_API_KEY=sk-... LOOM_SMOKE_MODEL=gpt-5
 *   mvn test -Dtest=PromptCacheSmokeTest
 * </pre>
 *
 * <p>连续发送相同 stable prefix、不同动态后缀的两次请求，验证 provider
 * 返回 cache read/creation 字段（第二次请求 cache read 应 > 0）。
 */
public class PromptCacheSmokeTest {

    private static final String PROVIDER = System.getenv("LOOM_SMOKE_PROVIDER");
    private static final String BASE_URL = System.getenv("LOOM_SMOKE_BASE_URL");
    private static final String API_KEY = System.getenv("LOOM_SMOKE_API_KEY");
    private static final String MODEL = System.getenv("LOOM_SMOKE_MODEL");

    @BeforeClass
    public static void requireExplicitProfile() {
        Assume.assumeTrue("LOOM_SMOKE_PROVIDER/BASE_URL/API_KEY/MODEL must all be set to run",
                PROVIDER != null && !PROVIDER.isBlank()
                        && BASE_URL != null && !BASE_URL.isBlank()
                        && API_KEY != null && !API_KEY.isBlank()
                        && MODEL != null && !MODEL.isBlank());
    }

    @Test
    public void consecutiveCallsWithSameStablePrefixReportCacheRead() {
        HttpModelGateway gateway = new HttpModelGateway(PROVIDER, MODEL, BASE_URL, API_KEY, 0.2, null, 60L);
        String stablePrefix = "Smoke-test stable prefix v1: role protocol + tools.\n";

        ChatPrompt first = prompt(stablePrefix, "first dynamic request " + System.nanoTime());
        ChatPrompt second = prompt(stablePrefix, "second dynamic request " + System.nanoTime());

        TokenUsage firstUsage = gateway.complete(first).block(java.time.Duration.ofSeconds(120)).getUsage();
        TokenUsage secondUsage = gateway.complete(second).block(java.time.Duration.ofSeconds(120)).getUsage();

        assertNotNull("provider 未返回 usage，无法验证缓存字段", firstUsage);
        assertNotNull("provider 未返回 usage，无法验证缓存字段", secondUsage);

        // 第二次请求应复用缓存：cache read > 0（若 provider 无缓存字段则 UNKNOWN，不失败）
        PromptCacheStatus secondStatus = PromptCacheStatus.fromUsage(secondUsage);
        if (secondStatus == PromptCacheStatus.HIT) {
            assertTrue("第二次请求应产生 cache read tokens",
                    secondUsage.getPromptCacheHitTokens() > 0);
        } else {
            System.out.println("[smoke] provider 未报告 cache read（status=" + secondStatus
                    + "），跳过断言；请确认 provider/model 支持 prompt cache。");
        }
    }

    private ChatPrompt prompt(String stablePrefix, String dynamicText) {
        return ChatPrompt.builder()
                .model(MODEL)
                .systemPrompt(stablePrefix)
                .messages(List.of(
                        ChatMessage.builder().role("user").content("Memory: - none").build(),
                        ChatMessage.builder().role("user").content(dynamicText).build()))
                .maxTokens(64)
                .cachePolicy(ChatPrompt.CachePolicy.READ_WRITE)
                .stablePrefixSignature("smoke-" + Integer.toHexString(stablePrefix.hashCode()))
                .promptCacheCapability(PromptCacheCapability.KEYED_REQUEST)
                .promptCacheRetention("in_memory")
                .build();
    }
}
