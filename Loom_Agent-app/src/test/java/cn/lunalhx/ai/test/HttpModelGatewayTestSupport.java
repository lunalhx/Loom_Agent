package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import cn.lunalhx.ai.infrastructure.gateway.HttpModelGateway;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Reflection helper exposing HttpModelGateway's package-private usage parsing
 * so PromptCacheTest can verify provider cache-field handling offline.
 */
public final class HttpModelGatewayTestSupport {

    private HttpModelGatewayTestSupport() {
    }

    public static TokenUsage parseUsage(Map<?, ?> usage) {
        try {
            HttpModelGateway gateway = new HttpModelGateway("deepseek", "deepseek-v4-flash",
                    "http://unused", "", 0.2, 0.9, 30L);
            Method method = HttpModelGateway.class.getDeclaredMethod("usage", Map.class);
            method.setAccessible(true);
            return (TokenUsage) method.invoke(gateway, usage);
        } catch (Exception e) {
            throw new RuntimeException("cannot parse usage", e);
        }
    }
}
