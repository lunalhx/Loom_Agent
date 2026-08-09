package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.PromptCacheCapability;
import cn.lunalhx.ai.infrastructure.gateway.HttpModelGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Gateway contract tests: capture the request JSON on a local mock HTTP server
 * and assert structure, cache fields, no-cache fallback and usage parsing.
 */
public class GatewayContractTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> bodies = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange));
        server.setExecutor(null);
        server.start();
    }

    @After
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        bodies.add(body);
        lastBody.set(body);
        requests.incrementAndGet();
        String response = mockResponse(exchange.getRequestURI().getPath());
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String mockResponse(String path) {
        if (path.endsWith("/messages")) {
            return "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                    + "\"usage\":{\"input_tokens\":1000,\"output_tokens\":200,"
                    + "\"cache_read_input_tokens\":800,\"cache_creation_input_tokens\":200}}";
        }
        if (path.endsWith("/responses")) {
            return "{\"output_text\":\"ok\",\"usage\":{\"input_tokens\":1000,"
                    + "\"output_tokens\":200,\"total_tokens\":1200,"
                    + "\"prompt_tokens_details\":{\"cached_tokens\":700}}}";
        }
        return "{\"response\":\"ok\"}";
    }

    private HttpModelGateway gateway(String provider) {
        return new HttpModelGateway(provider, "test-model", "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", 0.2, 0.9, 10L);
    }

    private ChatPrompt prompt(String systemPrompt, List<ChatMessage> messages) {
        return ChatPrompt.builder()
                .model("test-model")
                .systemPrompt(systemPrompt)
                .messages(messages)
                .maxTokens(512)
                .cachePolicy(ChatPrompt.CachePolicy.READ)
                .stablePrefixSignature("fp-stable")
                .build();
    }

    @Test
    public void deepseekPayloadKeepsStructuredSystemAndMessages() throws Exception {
        HttpModelGateway g = gateway("deepseek");
        ModelChatResult result = g.complete(prompt("system-prefix",
                List.of(ChatMessage.builder().role("user").content("history").build(),
                        ChatMessage.builder().role("user").content("current-request").build())))
                .block(java.time.Duration.ofSeconds(5));

        assertEquals("ok", result.getContent());
        JsonNode root = mapper.readTree(lastBody.get());
        assertTrue(root.has("system"));
        assertEquals("system-prefix", root.get("system").get(0).get("text").asText());
        assertEquals(2, root.get("messages").size());
        assertFalse(root.get("messages").get(0).get("content").toString().contains("cache_control"));
        // usage parsed
        assertEquals(800, (int) result.getUsage().getPromptCacheHitTokens());
        assertEquals(200, (int) result.getUsage().getPromptCacheMissTokens());
        assertEquals(cn.lunalhx.ai.domain.model.valobj.PromptCacheStatus.HIT, result.getUsage().getCacheStatus());
    }

    @Test
    public void unsupportedCapabilityOmitsCacheFields() throws Exception {
        HttpModelGateway g = gateway("deepseek");
        // No capability configured → no cache fields in payload.
        g.complete(prompt("system-prefix", List.of())).block(java.time.Duration.ofSeconds(5));
        JsonNode root = mapper.readTree(lastBody.get());
        assertFalse(root.toString().contains("cache_control"));
        assertFalse(root.toString().contains("prompt_cache"));
    }

    @Test
    public void ollamaPayloadHasNoCacheFields() throws Exception {
        HttpModelGateway g = gateway("ollama");
        g.complete(prompt("system-prefix", List.of(ChatMessage.builder().role("user").content("q").build())))
                .block(java.time.Duration.ofSeconds(5));
        JsonNode root = mapper.readTree(lastBody.get());
        assertTrue(root.has("prompt"));
        assertFalse(root.toString().contains("cache"));
    }

    @Test
    public void openaiResponsesMapsCacheKeyWhenCapabilityDeclared() throws Exception {
        HttpModelGateway g = gateway("openai");
        ChatPrompt p = prompt("system-prefix", List.of());
        p.setPromptCacheCapability(PromptCacheCapability.KEYED_REQUEST);
        p.setPromptCacheRetention("in_memory");
        g.complete(p).block(java.time.Duration.ofSeconds(5));

        JsonNode root = mapper.readTree(lastBody.get());
        assertTrue(root.has("prompt_cache_key"));
        // key 由 gateway 从 stable prefix 签名派生（provider namespace + model + prefix）
        assertEquals(cn.lunalhx.ai.domain.model.service.PromptCacheKeyFactory.deriveKey(
                "openai", "test-model", "fp-stable"), root.get("prompt_cache_key").asText());
        assertEquals("in_memory", root.get("prompt_cache_retention").asText());
        // system stays structured, current request separate
        assertEquals("system", root.get("input").get(0).get("role").asText());
    }

    @Test
    public void cacheRejectedWith400FallsBackOnceWithoutCacheFields() throws Exception {
        // First request answers with a 400 cache-parameter rejection; retry must be clean.
        AtomicInteger rejections = new AtomicInteger();
        HttpServer rejectServer = null;
        try {
            rejectServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            rejectServer.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                lastBody.set(body);
                if (body.contains("prompt_cache_key") && rejections.getAndIncrement() == 0) {
                    byte[] err = ("{\"error\":{\"type\":\"invalid_request_error\",\"message\":"
                            + "\"Unrecognized request argument supplied: prompt_cache_key\"}}")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(400, err.length);
                    exchange.getResponseBody().write(err);
                    exchange.close();
                    return;
                }
                byte[] ok = "{\"output_text\":\"ok\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, ok.length);
                exchange.getResponseBody().write(ok);
                exchange.close();
            });
            rejectServer.setExecutor(null);
            rejectServer.start();

            HttpModelGateway g = new HttpModelGateway("openai", "test-model",
                    "http://127.0.0.1:" + rejectServer.getAddress().getPort(), "k", null, null, 10L);
            ChatPrompt p = prompt("system-prefix", List.of());
            p.setPromptCacheCapability(PromptCacheCapability.KEYED_REQUEST);
            p.setPromptCacheRetention("in_memory");
            ModelChatResult degraded = g.complete(p).block(java.time.Duration.ofSeconds(5));

            // 1 rejection + 1 clean retry without cache fields
            assertEquals(1, rejections.get());
            assertFalse(lastBody.get().contains("prompt_cache_key"));
            assertNotNull(degraded);
            assertEquals("prompt_cache_parameter_rejected", degraded.getFallbackReason());
        } finally {
            if (rejectServer != null) {
                rejectServer.stop(0);
            }
        }
    }

    @Test
    public void messageBlockCacheControlLandsOnSystemOnly() throws Exception {
        HttpModelGateway g = gateway("deepseek");
        ChatPrompt p = prompt("system-prefix",
                List.of(ChatMessage.builder().role("user").content("history").build(),
                        ChatMessage.builder().role("user").content("current-request").build()));
        p.setPromptCacheCapability(PromptCacheCapability.MESSAGE_BLOCK);
        p.setPromptCacheRetention("in_memory");
        g.complete(p).block(java.time.Duration.ofSeconds(5));

        JsonNode root = mapper.readTree(lastBody.get());
        // cache_control 只落在 system 段（稳定边界），动态 message 不带断点
        assertTrue(root.get("system").get(0).get("text").asText().contains("system-prefix"));
        assertTrue(root.get("system").get(1).has("cache_control"));
        for (JsonNode message : root.get("messages")) {
            assertFalse(message.get("content").toString().contains("cache_control"));
        }
    }
}
