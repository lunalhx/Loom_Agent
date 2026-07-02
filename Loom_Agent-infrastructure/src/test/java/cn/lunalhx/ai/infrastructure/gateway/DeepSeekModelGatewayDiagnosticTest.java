package cn.lunalhx.ai.infrastructure.gateway;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.valobj.ModelCapabilities;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import cn.lunalhx.ai.infrastructure.gateway.diagnostics.CacheDiagnosticCategory;
import cn.lunalhx.ai.infrastructure.gateway.diagnostics.CacheDiagnosticResult;
import cn.lunalhx.ai.infrastructure.gateway.diagnostics.PromptCacheDiagnosticContext;
import cn.lunalhx.ai.infrastructure.gateway.diagnostics.PromptCacheDiagnosticHook;
import cn.lunalhx.ai.infrastructure.gateway.diagnostics.PromptCacheDiagnosticProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 把 {@link PromptCacheDiagnosticHook} 接到 {@link DeepSeekModelGateway}「最终 payload 构造与发送路径」
 * 的契约测试。覆盖：
 * <ul>
 *   <li>每次请求 body 只序列化一次（ObjectMapper.writeValueAsString 调用计数 == 1）；</li>
 *   <li>complete / stream 两条路径都接入 hook；</li>
 *   <li>Authorization header / API key 永不进入诊断输入（hook 看到的字符串与 body publisher 的字符串是同一引用，headers 独立设置）；</li>
 *   <li>retry 同模型复用 payload（IDENTICAL）；fallback 模型独立序列（FIRST_REQUEST）。</li>
 * </ul>
 *
 * <p>使用 loopback HTTP server 模拟 DeepSeek API，避免引入额外的 mock 框架。
 */
public class DeepSeekModelGatewayDiagnosticTest {

    private HttpServer server;
    private ThreadPoolExecutor executor;

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    // ---------- 单次序列化不变量 ----------

    @Test
    public void completePathSerializesBodyExactlyOnce() throws Exception {
        TestHarness harness = newHarness("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,"
                + "\"total_tokens\":2,\"prompt_cache_hit_tokens\":1,\"prompt_cache_miss_tokens\":0}}", null);
        try {
            DeepSeekModelGateway gateway = harness.gateway();
            Mono.fromCallable(() -> gateway.complete(samplePrompt("conv-A", ModelCallPurpose.FINAL_TEXT)))
                    .flatMap(m -> m)
                    .block();

            // 关键不变量：完成一次完整请求，ObjectMapper.writeValueAsString 必须只被调用 1 次
            assertEquals("body 序列化必须恰好 1 次（与 HTTP BodyPublisher 共享同一字符串）",
                    1, harness.serializationCount());
            // 诊断 hash 必须等于实际发送 body 的 hash
            assertEquals("complete 路径诊断 hash 必须等于实际发送 body 的 SHA-256",
                    harness.bodyHashOfLastSend(), harness.lastDiagnosticHash());
            // 初次调用：诊断类别为 FIRST_REQUEST
            assertEquals(CacheDiagnosticCategory.FIRST_REQUEST, harness.lastDiagnosticCategory());
        } finally {
            harness.close();
        }
    }

    @Test
    public void streamPathSerializesBodyExactlyOnce() throws Exception {
        TestHarness harness = newHarness(null, "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"b\"}}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,"
                + "\"total_tokens\":7,\"prompt_cache_hit_tokens\":3,\"prompt_cache_miss_tokens\":2}}\n\n"
                + "data: [DONE]\n\n");
        try {
            DeepSeekModelGateway gateway = harness.gateway();
            Flux<ModelStreamChunk> stream = gateway.stream(samplePrompt("conv-B", ModelCallPurpose.FINAL_TEXT));
            List<ModelStreamChunk> chunks = new ArrayList<>();
            StepVerifier.create(stream).recordWith(ArrayList::new).thenConsumeWhile(c -> true).verifyComplete();
            // Stream 路径同样只序列化 1 次
            assertEquals("stream 路径 body 序列化必须恰好 1 次",
                    1, harness.serializationCount());
            assertEquals("stream 路径诊断 hash 必须等于实际发送 body 的 SHA-256",
                    harness.bodyHashOfLastSend(), harness.lastDiagnosticHash());
        } finally {
            harness.close();
        }
    }

    // ---------- complete / stream 两条路径都接入 ----------

    @Test
    public void completeAndStreamBothInvokeHookBeforeAndAfterSend() throws Exception {
        TestHarness harness = newHarness("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,"
                + "\"total_tokens\":2}}", null);
        try {
            DeepSeekModelGateway gateway = harness.gateway();
            gateway.complete(samplePrompt("conv-c", ModelCallPurpose.FINAL_TEXT)).block();
            assertEquals("complete 路径 must call beforeSend exactly once",
                    1, harness.beforeSendCount());
            assertEquals("complete 路径 must call afterSend exactly once",
                    1, harness.afterSendCount());

            // 再走一次 stream，计数应继续累加
            TestHarness streamHarness = newHarness(null, "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n"
                    + "data: [DONE]\n\n");
            try {
                DeepSeekModelGateway streamGateway = streamHarness.gateway();
                streamGateway.stream(samplePrompt("conv-s", ModelCallPurpose.FINAL_TEXT))
                        .collectList().block();
                assertEquals("stream 路径 must call beforeSend exactly once",
                        1, streamHarness.beforeSendCount());
                assertEquals("stream 路径 must call afterSend exactly once",
                        1, streamHarness.afterSendCount());
            } finally {
                streamHarness.close();
            }
        } finally {
            harness.close();
        }
    }

    // ---------- Authorization / API key 永不进入诊断 ----------

    @Test
    public void authorizationAndApiKeyNeverEnterDiagnosticInput() throws Exception {
        CapturingHandler capturing = new CapturingHandler(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                        + "\"finish_reason\":\"stop\"}],\"usage\":{}}", null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", capturing);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        executor = new ThreadPoolExecutor(2, 4, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.ai.deepseek.chat.api-key", "sk-supersecret-key-DO-NOT-LEAK");
        env.setProperty("spring.ai.deepseek.chat.base-url", baseUrl);
        env.setProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash");

        // 1) 启用诊断；用 RecordingHook 包装以便断言 raw payload 与 result
        PromptCacheDiagnosticProperties props = new PromptCacheDiagnosticProperties();
        props.setEnabled(true);
        props.setLogRedactedBody(true);
        RecordingHook recordingHook = new RecordingHook(props);
        DeepSeekModelGateway gateway = new DeepSeekModelGateway(env, new ObjectMapper(),
                new ModelRuntimeProperties(), executor, recordingHook);

        gateway.complete(samplePrompt("conv-secret", ModelCallPurpose.FINAL_TEXT)).block();

        // 2) 服务器侧捕获的 body：必须不包含任何 Authorization / api-key
        assertEquals("complete 路径服务器必须收到恰好 1 次请求", 1, capturing.requestBodies.size());
        String sentBody = capturing.requestBodies.get(0);
        assertFalse("实际下发的 body 不应包含 Authorization header 字符串",
                sentBody.contains("Authorization"));
        assertFalse("实际下发的 body 不应包含 Bearer 前缀",
                sentBody.contains("Bearer "));
        assertFalse("实际下发的 body 不应包含 API key 字面量",
                sentBody.contains("sk-supersecret-key-DO-NOT-LEAK"));
        // 3) 服务器侧捕获的请求头：Authorization 独立设置，不与 body 字符串共享通道
        assertTrue("Authorization 头必须独立设置且包含 API key",
                capturing.authorizationHeader.contains("sk-supersecret-key-DO-NOT-LEAK"));
        // 4) hook 的 rawPayload 必须等于实际发送的 body（同一字符串）
        assertEquals("hook 接收的 body 字符串必须等于服务器实际收到的 body",
                sentBody, recordingHook.lastSeenRawPayload());
        // 5) hook 的 result.toString 不泄露 API key
        assertFalse("result.toString 不能泄露 API key",
                recordingHook.lastSeenResult().toString().contains("sk-supersecret-key-DO-NOT-LEAK"));
    }

    // ---------- retry / fallback 通过完整 gateway + hook 的语义 ----------

    @Test
    public void retrySameModelReportsIdenticalOnSecondCall() throws Exception {
        TestHarness harness = newHarness("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{}}", null);
        try {
            DeepSeekModelGateway gateway = harness.gateway();
            ChatPrompt prompt = samplePrompt("conv-r", ModelCallPurpose.FINAL_TEXT);

            // 第一次
            gateway.complete(prompt).block();
            assertEquals(CacheDiagnosticCategory.FIRST_REQUEST, harness.lastDiagnosticCategory());
            String recordedFirstHash = harness.lastDiagnosticHash();

            // 第二次（语义上等同 retry：同一 model + capability + purpose + conversation）
            gateway.complete(prompt).block();
            // 第二次的 currentHash 必须 == 第一次的 currentHash（payload 完全相同）
            String recordedSecondHash = harness.lastDiagnosticHash();
            assertEquals("retry 同一模型 payload 必须复用同一字符串",
                    recordedFirstHash, recordedSecondHash);
            assertEquals("第二次诊断类别应为 IDENTICAL",
                    CacheDiagnosticCategory.IDENTICAL, harness.lastDiagnosticCategory());
        } finally {
            harness.close();
        }
    }

    @Test
    public void fallbackToDifferentModelUsesIndependentSequence() throws Exception {
        TestHarness harness = newHarness("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{}}", null);
        try {
            DeepSeekModelGateway gateway = harness.gateway();
            // 主模型 flash
            ChatPrompt flash = samplePrompt("conv-fb", ModelCallPurpose.FINAL_TEXT, "deepseek-v4-flash");
            gateway.complete(flash).block();
            assertEquals(CacheDiagnosticCategory.FIRST_REQUEST, harness.lastDiagnosticCategory());
            String recordedFlashHash = harness.lastDiagnosticHash();

            // fallback 到 pro 模型：独立序列，仍是 FIRST_REQUEST
            ChatPrompt pro = samplePrompt("conv-fb", ModelCallPurpose.FINAL_TEXT, "deepseek-v4-pro");
            gateway.complete(pro).block();
            assertEquals("fallback 切到 pro 后必须视为 FIRST_REQUEST（独立比较序列）",
                    CacheDiagnosticCategory.FIRST_REQUEST, harness.lastDiagnosticCategory());
            String recordedProHash = harness.lastDiagnosticHash();
            assertFalse("fallback 模型的 currentHash 必须不等于主模型的 currentHash",
                    recordedFlashHash.equals(recordedProHash));
        } finally {
            harness.close();
        }
    }

    // ---------- 辅助 ----------

    private ChatPrompt samplePrompt(String conversationId, ModelCallPurpose purpose) {
        return samplePrompt(conversationId, purpose, null);
    }

    private ChatPrompt samplePrompt(String conversationId, ModelCallPurpose purpose, String model) {
        return ChatPrompt.builder()
                .requestId("req-" + conversationId)
                .conversationId(conversationId)
                .message("hello")
                .model(model)
                .capability(ModelCapabilities.STREAM_CHAT)
                .purpose(purpose)
                .messages(List.of(ChatMessage.builder().role("user").content("hi").build()))
                .build();
    }

    private TestHarness newHarness(String completeBody, String streamBody) throws IOException {
        CapturingHandler capturing = new CapturingHandler(completeBody, streamBody);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", capturing);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        executor = new ThreadPoolExecutor(2, 4, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        return new TestHarness(baseUrl, executor, server, capturing);
    }

    /**
     * 计数 + 代理 ObjectMapper。继承 ObjectMapper 是为了让 DeepSeekModelGateway 直接 new 它而无需改任何代码。
     */
    private static final class CountingObjectMapper extends ObjectMapper {
        private final AtomicInteger writes = new AtomicInteger();
        private final AtomicReference<String> lastWrite = new AtomicReference<>();

        @Override
        public String writeValueAsString(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
            String s = super.writeValueAsString(value);
            writes.incrementAndGet();
            lastWrite.set(s);
            return s;
        }

        int count() {
            return writes.get();
        }

        String lastWrite() {
            return lastWrite.get();
        }
    }

    /**
     * 包装 Hook，记录诊断上下文用于断言。
     */
    private static final class RecordingHook extends PromptCacheDiagnosticHook {
        private final AtomicInteger beforeSendCount = new AtomicInteger();
        private final AtomicInteger afterSendCount = new AtomicInteger();
        private final AtomicReference<PromptCacheDiagnosticContext> lastContext = new AtomicReference<>();
        private final AtomicReference<TokenUsage> lastUsage = new AtomicReference<>();
        private volatile String lastSeenRawPayload;
        private volatile CacheDiagnosticResult lastSeenResult;

        RecordingHook(PromptCacheDiagnosticProperties properties) {
            super(properties);
        }

        @Override
        public PromptCacheDiagnosticContext beforeSend(String model, String capability, String purpose,
                                                       String conversationId, String rawPayload,
                                                       List<Map<String, String>> currentMessages) {
            beforeSendCount.incrementAndGet();
            PromptCacheDiagnosticContext ctx = super.beforeSend(model, capability, purpose, conversationId,
                    rawPayload, currentMessages);
            if (ctx != null) {
                lastContext.set(ctx);
                lastSeenRawPayload = ctx.rawPayload();
                lastSeenResult = ctx.result();
            }
            return ctx;
        }

        @Override
        public void afterSend(PromptCacheDiagnosticContext context, TokenUsage usage) {
            afterSendCount.incrementAndGet();
            if (usage != null) {
                lastUsage.set(usage);
            }
            super.afterSend(context, usage);
        }

        int beforeSendCount() {
            return beforeSendCount.get();
        }

        int afterSendCount() {
            return afterSendCount.get();
        }

        CacheDiagnosticCategory lastDiagnosticCategory() {
            PromptCacheDiagnosticContext ctx = lastContext.get();
            return ctx == null ? null : ctx.result().category();
        }

        String lastDiagnosticHash() {
            PromptCacheDiagnosticContext ctx = lastContext.get();
            return ctx == null ? null : ctx.result().currentRawPayloadHash();
        }

        TokenUsage lastUsage() {
            return lastUsage.get();
        }

        String lastSeenRawPayload() {
            return lastSeenRawPayload;
        }

        CacheDiagnosticResult lastSeenResult() {
            return lastSeenResult;
        }
    }

    /**
     * 测试承载：HTTP server + 计数 ObjectMapper + 录制 hook + 线程池生命周期管理。
     */
    private final class TestHarness {
        private final HttpServer server;
        private final ThreadPoolExecutor executor;
        private final CountingObjectMapper objectMapper;
        private final RecordingHook hook;
        private final DeepSeekModelGateway gateway;
        private final MockEnvironment env;
        private final CapturingHandler handler;

        TestHarness(String baseUrl, ThreadPoolExecutor executor, HttpServer server, CapturingHandler handler) {
            this.server = server;
            this.executor = executor;
            this.objectMapper = new CountingObjectMapper();
            this.handler = handler;

            env = new MockEnvironment();
            env.setProperty("spring.ai.deepseek.chat.api-key", "test-key");
            env.setProperty("spring.ai.deepseek.chat.base-url", baseUrl);
            env.setProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash");

            PromptCacheDiagnosticProperties props = new PromptCacheDiagnosticProperties();
            props.setEnabled(true);
            this.hook = new RecordingHook(props);
            this.gateway = new DeepSeekModelGateway(env, objectMapper, new ModelRuntimeProperties(),
                    executor, hook);
        }

        DeepSeekModelGateway gateway() {
            return gateway;
        }

        int serializationCount() {
            return objectMapper.count();
        }

        String bodyHashOfLastSend() {
            // 服务器实际收到的 body 字符串的 SHA-256（来自 CapturingHandler.lastReceivedBody）
            String body = handler.lastReceivedBody.get();
            if (body == null) {
                return null;
            }
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(body.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        CacheDiagnosticCategory lastDiagnosticCategory() {
            return hook.lastDiagnosticCategory();
        }

        String lastDiagnosticHash() {
            return hook.lastDiagnosticHash();
        }

        int beforeSendCount() {
            return hook.beforeSendCount();
        }

        int afterSendCount() {
            return hook.afterSendCount();
        }

        void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    /**
     * 模拟 DeepSeek 的 HTTP handler：返回固定 complete body 或固定 SSE 串，并记录下发的 body 与 Authorization 头。
     */
    private static final class CapturingHandler implements HttpHandler {
        final List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<String> lastReceivedBody = new AtomicReference<>();
        volatile String authorizationHeader;
        private final String completeBody;
        private final String streamBody;

        CapturingHandler(String completeBody, String streamBody) {
            this.completeBody = completeBody;
            this.streamBody = streamBody;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String bodyStr = new String(body, StandardCharsets.UTF_8);
            requestBodies.add(bodyStr);
            lastReceivedBody.set(bodyStr);
            authorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");

            byte[] responseBytes;
            exchange.getResponseHeaders().add("Content-Type",
                    streamBody != null ? "text/event-stream" : "application/json");
            if (streamBody != null) {
                responseBytes = streamBody.getBytes(StandardCharsets.UTF_8);
            } else {
                responseBytes = (completeBody != null
                        ? completeBody
                        : "{\"choices\":[]}").getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }
}
