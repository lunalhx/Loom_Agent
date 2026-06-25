package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import cn.lunalhx.ai.infrastructure.gateway.DeepSeekModelGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeepSeekModelGatewayErrorMappingTest {

    @Test
    public void shouldOmitUserIdAndKeepJsonResponseFormat() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 2, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            DeepSeekModelGateway gateway = new DeepSeekModelGateway(
                    new MockEnvironment(), objectMapper, new ModelRuntimeProperties(), executor);
            Method method = DeepSeekModelGateway.class.getDeclaredMethod(
                    "toRequestBody", ChatPrompt.class, boolean.class);
            method.setAccessible(true);

            String requestBody = (String) method.invoke(gateway, ChatPrompt.builder()
                    .conversationId("conversation/with unsupported characters")
                    .message("return json")
                    .outputFormat(OutputFormat.JSON_OBJECT)
                    .build(), false);
            JsonNode body = objectMapper.readTree(requestBody);

            assertFalse(body.has("user_id"));
            assertTrue(body.has("response_format"));
            assertEquals("json_object", body.path("response_format").path("type").asText());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldSerializeReminderAsIndependentUserMessageAfterMainPrompt() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 2, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            DeepSeekModelGateway gateway = new DeepSeekModelGateway(
                    new MockEnvironment(), objectMapper, new ModelRuntimeProperties(), executor);
            Method method = DeepSeekModelGateway.class.getDeclaredMethod(
                    "toRequestBody", ChatPrompt.class, boolean.class);
            method.setAccessible(true);

            String mainPrompt = "用户问题：给某模块加缓存并补单测\n当前计划：\n- [in_progress] task-1: 检查目标代码";
            String reminder = "<reminder>Update your todos with todo_write before continuing.</reminder>";
            String requestBody = (String) method.invoke(gateway, ChatPrompt.builder()
                    .conversationId("conversation-reminder")
                    .messages(List.of(
                            ChatMessage.builder().role("user").content(mainPrompt).build(),
                            ChatMessage.builder().role("user").content(reminder).build()))
                    .outputFormat(OutputFormat.JSON_OBJECT)
                    .build(), false);
            JsonNode body = objectMapper.readTree(requestBody);

            ArrayNode messages = (ArrayNode) body.path("messages");
            assertEquals(3, messages.size());

            // 顺序：JSON 输出约束的 system message → 主提示词 user message → reminder user message。
            assertEquals("system", messages.get(0).path("role").asText());
            assertTrue(messages.get(0).path("content").asText().contains("请只输出一个合法 JSON 对象"));
            assertEquals("user", messages.get(1).path("role").asText());
            assertEquals(mainPrompt, messages.get(1).path("content").asText());
            assertEquals("user", messages.get(2).path("role").asText());
            assertEquals(reminder, messages.get(2).path("content").asText());

            // 主提示词只发送一次，reminder 不混入主提示词。
            assertEquals(1, objectMapper.readTree(requestBody).path("messages")
                    .toString().split("给某模块加缓存并补单测").length - 1);
            assertFalse(messages.get(1).path("content").asText().contains("<reminder>"));
            assertTrue(body.has("response_format"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldMapOnlyExplicitContextErrorsToContextOverflow() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 2, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            DeepSeekModelGateway gateway = new DeepSeekModelGateway(
                    new MockEnvironment(), new ObjectMapper(), new ModelRuntimeProperties(), executor);
            Method method = DeepSeekModelGateway.class.getDeclaredMethod(
                    "toHttpException", int.class, String.class, HttpHeaders.class, String.class);
            method.setAccessible(true);

            ModelGatewayException ordinary400 = (ModelGatewayException) method.invoke(gateway, 400,
                    "{\"error\":{\"message\":\"temperature must be between 0 and 2\"}}",
                    HttpHeaders.of(Map.of(), (a, b) -> true), "deepseek-v4-flash");
            ModelGatewayException context422 = (ModelGatewayException) method.invoke(gateway, 422,
                    "{\"error\":{\"message\":\"context_length_exceeded: tokens exceed limit\"}}",
                    HttpHeaders.of(Map.of(), (a, b) -> true), "deepseek-v4-flash");

            assertEquals(ModelErrorCode.BAD_REQUEST, ordinary400.getErrorCode());
            assertEquals(ModelErrorCode.CONTEXT_OVERFLOW, context422.getErrorCode());
        } finally {
            executor.shutdownNow();
        }
    }

}
