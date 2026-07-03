package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import cn.lunalhx.ai.infrastructure.gateway.DeepSeekModelGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.*;

/**
 * Verifies that shadow mode (enabled=false, shadowEnabled=true) does NOT
 * alter the actual request sent to the model.
 *
 * <p>Uses the real ModelPromptFactory.build() via reflection (package-private)
 * and DeepSeekModelGateway.toRequestBody() via reflection — NO manual
 * ChatPrompt construction, NO network calls.
 */
public class ModelPromptFactoryShadowCompatibilityTest {

    private StablePrefix stablePrefix;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        stablePrefix = new StablePrefix("frozen-protocol-content",
                "sha256-test");
        objectMapper = new ObjectMapper();
    }

    private AgentContext buildContext(String runId) {
        AgentContext ctx = new AgentContext();
        ctx.setRunId(runId);
        ctx.setRootRunId(runId);
        ctx.setRequestId("req-" + runId);
        ctx.setConversationId("conv-" + runId);
        ctx.setQuestion("用 Java 实现一个缓存层并写单测");
        ctx.setStep(0);
        ctx.setMaxSteps(30);
        ctx.setMaxSegments(1);
        ctx.setMaxTotalSteps(30);
        ctx.setSegmentIndex(0);
        ctx.setSegmentStartStep(0);
        ctx.setCurrentPrompt("rendered old prompt content for testing");
        return ctx;
    }

    /** Reflectively invoke ModelPromptFactory.build() since it's package-private. */
    private ChatPrompt invokeFactoryBuild(AgentContext ctx, String model, int maxTokens,
                                           long deadlineMs) throws Exception {
        Class<?> factoryClass = Class.forName(
                "cn.lunalhx.ai.domain.agent.flow.node.ModelPromptFactory");
        Constructor<?> ctor = factoryClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object factory = ctor.newInstance();
        Method buildMethod = factoryClass.getDeclaredMethod(
                "build", AgentContext.class, String.class, int.class, long.class);
        buildMethod.setAccessible(true);
        return (ChatPrompt) buildMethod.invoke(factory, ctx, model, maxTokens, deadlineMs);
    }

    // ================================================================
    // 1. Real ModelPromptFactory produces old system+user structure
    // ================================================================

    @Test
    public void factoryProducesOldSystemPlusUserStructure() throws Exception {
        AgentContext ctx = buildContext("r-factory-1");
        ChatPrompt prompt = invokeFactoryBuild(ctx, "deepseek-v4", 4096, 0);

        assertNotNull("systemPrompt must be populated", prompt.getSystemPrompt());
        assertEquals("rendered old prompt content for testing", prompt.getMessage());
        assertNull("messages list must be null for single-message path",
                prompt.getMessages());
        assertEquals(OutputFormat.JSON_OBJECT, prompt.getOutputFormat());
    }

    @Test
    public void factoryWithReminderProducesMessagesList() throws Exception {
        AgentContext ctx = buildContext("r-factory-2");
        AgentPlan plan = AgentPlan.forQuestion("test");
        ctx.setPlan(plan);
        plan.setRoundsSinceUpdate(5);

        ChatPrompt prompt = invokeFactoryBuild(ctx, "deepseek-v4", 4096, 0);

        // Reminder path → messages list with 2 user messages
        assertNotNull("messages must be populated for reminder path",
                prompt.getMessages());
        assertEquals(2, prompt.getMessages().size());
        assertEquals("user", prompt.getMessages().get(0).getRole());
        assertEquals("user", prompt.getMessages().get(1).getRole());
        assertThat(prompt.getMessages().get(1).getContent())
                .contains("Update your todos with todo_write");
        assertNull("message field must be null when messages list is used",
                prompt.getMessage());
    }

    // ================================================================
    // 2. Gateway serialization is unchanged by shadow mode
    // ================================================================

    @Test
    public void gatewayRequestBodyUsesOldStructure() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 2, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            DeepSeekModelGateway gateway = new DeepSeekModelGateway(
                    new MockEnvironment(), new ObjectMapper(),
                    new ModelRuntimeProperties(), executor);

            Method toBody = DeepSeekModelGateway.class.getDeclaredMethod(
                    "toRequestBody", ChatPrompt.class, boolean.class);
            toBody.setAccessible(true);

            AgentContext ctx = buildContext("r-gw-1");
            ChatPrompt prompt = invokeFactoryBuild(ctx, "deepseek-v4", 4096, 0);

            String requestBody = (String) toBody.invoke(gateway, prompt, false);
            JsonNode body = objectMapper.readTree(requestBody);

            // Old structure: system + single user message
            JsonNode messages = body.get("messages");
            assertNotNull("messages array must exist", messages);
            assertTrue("messages must be an array", messages.isArray());

            assertEquals("system", messages.get(0).get("role").asText());
            assertThat(messages.get(0).get("content").asText())
                    .contains("请只输出一个合法 JSON 对象");

            assertEquals("user", messages.get(1).get("role").asText());
            assertEquals("rendered old prompt content for testing",
                    messages.get(1).get("content").asText());

            // Ledger/StablePrefix must NOT appear in request body
            assertThat(requestBody).doesNotContain("test-frozen-prefix");
            assertThat(requestBody).doesNotContain("conversationLedger");
            assertThat(requestBody).doesNotContain("StablePrefix");

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void gatewayWithReminderStructureUnchanged() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 2, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            DeepSeekModelGateway gateway = new DeepSeekModelGateway(
                    new MockEnvironment(), new ObjectMapper(),
                    new ModelRuntimeProperties(), executor);

            Method toBody = DeepSeekModelGateway.class.getDeclaredMethod(
                    "toRequestBody", ChatPrompt.class, boolean.class);
            toBody.setAccessible(true);

            AgentContext ctx = buildContext("r-gw-2");
            AgentPlan plan = AgentPlan.forQuestion("test");
            ctx.setPlan(plan);
            plan.setRoundsSinceUpdate(5);

            ChatPrompt prompt = invokeFactoryBuild(ctx, "deepseek-v4", 4096, 0);

            String requestBody = (String) toBody.invoke(gateway, prompt, false);
            JsonNode body = objectMapper.readTree(requestBody);
            JsonNode messages = body.get("messages");

            // Structure: system + user main prompt + user reminder
            assertEquals(3, messages.size());
            assertEquals("system", messages.get(0).get("role").asText());
            assertEquals("user", messages.get(1).get("role").asText());
            assertEquals("user", messages.get(2).get("role").asText());
            assertThat(messages.get(2).get("content").asText())
                    .contains("todo_write");
        } finally {
            executor.shutdownNow();
        }
    }
}
