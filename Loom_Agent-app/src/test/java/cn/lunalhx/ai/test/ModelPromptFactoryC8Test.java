package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedger;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedgerEntry;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.valobj.LedgerStableType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.*;

/**
 * C8: Verifies that ModelPromptFactory correctly switches between legacy
 * (enabled=false) and ledger-based (enabled=true) prompt construction.
 *
 * <p>Uses reflection to access package-private ModelPromptFactory and
 * DeepSeekModelGateway.toRequestBody() — NO network calls.
 */
public class ModelPromptFactoryC8Test {

    private StablePrefix stablePrefix;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        stablePrefix = new StablePrefix(
                "You are a helpful coding assistant.\n\n"
                        + "<role_protocol>\n"
                        + "You must output decisions in JSON format.\n"
                        + "</role_protocol>\n",
                "sha256-c8-test");
        objectMapper = new ObjectMapper();
    }

    // ================================================================
    // Helpers
    // ================================================================

    private AgentContext buildBaseContext(String runId, boolean withLedger) {
        AgentContext ctx = new AgentContext();
        ctx.setRunId(runId);
        ctx.setRootRunId(runId);
        ctx.setRequestId("req-" + runId);
        ctx.setConversationId("conv-" + runId);
        ctx.setQuestion("用 Java 实现一个缓存层并写单测");
        ctx.setStep(3);
        ctx.setMaxSteps(30);
        ctx.setMaxSegments(1);
        ctx.setMaxTotalSteps(30);
        ctx.setSegmentIndex(0);
        ctx.setSegmentStartStep(0);
        ctx.setStablePrefix(stablePrefix);

        if (withLedger) {
            ConversationLedger ledger = new ConversationLedger();
            // USER_TASK — the initial question
            ledger.appendWithEventKey("user", "用 Java 实现一个缓存层并写单测",
                    LedgerStableType.USER_TASK, runId + ":init:user_task");
            // ASSISTANT_ACTION — model response
            ledger.appendWithEventKey("assistant",
                    "{\"action\":\"todo_write\",\"todos\":[{\"id\":\"1\",\"content\":\"实现缓存层\",\"status\":\"pending\"}]}",
                    LedgerStableType.ASSISTANT_ACTION, runId + ":1:assistant");
            // TOOL_RESULT — tool output
            ledger.appendWithEventKey("user",
                    "<untrusted_tool_output>\nTodos updated.\n</untrusted_tool_output>",
                    LedgerStableType.TOOL_RESULT, runId + ":1:tool_result");
            // ASSISTANT_ACTION — next model response
            ledger.appendWithEventKey("assistant",
                    "{\"action\":\"write\",\"path\":\"Cache.java\",\"content\":\"...\"}",
                    LedgerStableType.ASSISTANT_ACTION, runId + ":2:assistant");
            // CONTROL_UPDATE — TODO reminder (already appended by ModelCallNode)
            ledger.appendWithEventKey("user",
                    "<reminder>Update your todos with todo_write before continuing.</reminder>",
                    LedgerStableType.CONTROL_UPDATE, runId + ":3:todo_reminder");
            ctx.setConversationLedger(ledger);
            ctx.setLedgerReady(true); // C9R: ledger must be bootstrapped for model input
        }

        return ctx;
    }

    /** Reflectively invoke ModelPromptFactory.build(). */
    private ChatPrompt invokeFactoryBuild(AgentContext ctx,
                                           String model, int maxTokens,
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

    /** Reflectively invoke ModelPromptFactory.budgetInput(). */
    private String invokeFactoryBudgetInput(AgentContext ctx)
            throws Exception {
        Class<?> factoryClass = Class.forName(
                "cn.lunalhx.ai.domain.agent.flow.node.ModelPromptFactory");
        Constructor<?> ctor = factoryClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object factory = ctor.newInstance();
        Method method = factoryClass.getDeclaredMethod(
                "budgetInput", AgentContext.class);
        method.setAccessible(true);
        return (String) method.invoke(factory, ctx);
    }

    /** DeepSeekModelGateway.toRequestBody() via reflection. */
    private String invokeGatewayToRequestBody(ChatPrompt prompt, boolean stream)
            throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 2, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            DeepSeekModelGateway gateway = new DeepSeekModelGateway(
                    new MockEnvironment(), new ObjectMapper(),
                    new ModelRuntimeProperties(), executor);
            Method toBody = DeepSeekModelGateway.class.getDeclaredMethod(
                    "toRequestBody", ChatPrompt.class, boolean.class);
            toBody.setAccessible(true);
            return (String) toBody.invoke(gateway, prompt, stream);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Build canonical messages text (same as what budgetInput should produce in ledger mode). */
    private String canonicalMessagesText(AgentContext ctx) {
        StringBuilder sb = new StringBuilder();
        StablePrefix sp = ctx.getStablePrefix();
        if (sp != null && sp.frozenContent() != null) {
            sb.append(sp.frozenContent());
        }
        ConversationLedger ledger = ctx.getConversationLedger();
        if (ledger != null) {
            for (ConversationLedgerEntry e : ledger.entries()) {
                sb.append(e.content());
            }
        }
        return sb.toString();
    }

    // ================================================================
    // 2. flag=true produces correct message roles and order
    // ================================================================

    @Test
    public void ledgerOnlyProducesCorrectRolesAndOrder() throws Exception {
        AgentContext ctx = buildBaseContext("r-c8-order", true);

        ChatPrompt prompt = invokeFactoryBuild(ctx, "deepseek-v4", 4096, 0);

        // System prompt is from StablePrefix, NOT the old SECURITY_SYSTEM_PROMPT
        assertEquals(stablePrefix.frozenContent(), prompt.getSystemPrompt());
        assertThat(prompt.getSystemPrompt()).doesNotContain("untrusted_tool_output");

        // message field must be null — ledger mode always uses messages list
        assertNull("message must be null in ledger mode", prompt.getMessage());

        // messages list populated from ledger entries in original order
        List<ChatMessage> messages = prompt.getMessages();
        assertNotNull("messages must not be null", messages);
        assertEquals(5, messages.size());

        // Verify role and order match ledger entries exactly
        assertEquals("user", messages.get(0).getRole());
        assertThat(messages.get(0).getContent()).contains("缓存层");

        assertEquals("assistant", messages.get(1).getRole());
        assertThat(messages.get(1).getContent()).contains("todo_write");

        assertEquals("user", messages.get(2).getRole());
        assertThat(messages.get(2).getContent()).contains("untrusted_tool_output");

        assertEquals("assistant", messages.get(3).getRole());
        assertThat(messages.get(3).getContent()).contains("Cache.java");

        assertEquals("user", messages.get(4).getRole());
        assertThat(messages.get(4).getContent()).contains("todo_write");
    }

    // ================================================================
    // 3. No reminder duplication (ledger already has it from ModelCallNode)
    // ================================================================

    @Test
    public void ledgerOnlyNoReminderDuplication() throws Exception {
        AgentContext ctx = buildBaseContext("r-c8-nodup", true);
        // Set high roundsSinceUpdate — in legacy mode this would trigger reminder
        AgentPlan plan = AgentPlan.forQuestion("test");
        ctx.setPlan(plan);
        plan.setRoundsSinceUpdate(5);

        ChatPrompt prompt = invokeFactoryBuild(ctx, "deepseek-v4", 4096, 0);

        // Count reminder occurrences across ALL messages
        long reminderCount = prompt.getMessages().stream()
                .filter(m -> m.getContent() != null
                        && m.getContent().contains("Update your todos with todo_write"))
                .count();

        assertEquals("reminder must appear exactly once (from ledger, not factory)",
                1, reminderCount);

        // Also verify systemPrompt doesn't contain the reminder
        assertThat(prompt.getSystemPrompt())
                .doesNotContain("Update your todos with todo_write");
    }

    @Test
    public void ledgerOnlyNoReminderWhenNotTriggered() throws Exception {
        // When roundsSinceUpdate is low, neither ledger NOR factory should have reminder
        AgentContext ctx = buildBaseContext("r-c8-norem", false);
        AgentPlan plan = AgentPlan.forQuestion("test");
        ctx.setPlan(plan);
        plan.setRoundsSinceUpdate(1);

        // Build context WITHOUT todo_reminder in ledger
        ConversationLedger ledger = new ConversationLedger();
        ledger.appendWithEventKey("user", "用 Java 实现一个缓存层并写单测",
                LedgerStableType.USER_TASK, "r-c8-norem:init:user_task");
        ledger.appendWithEventKey("assistant", "{\"action\":\"write\",\"path\":\"Test.java\"}",
                LedgerStableType.ASSISTANT_ACTION, "r-c8-norem:1:assistant");
        ctx.setConversationLedger(ledger);
        ctx.setStablePrefix(stablePrefix);
        ctx.setLedgerReady(true); // C9R

        ChatPrompt prompt = invokeFactoryBuild(ctx, "deepseek-v4", 4096, 0);

        // No reminder anywhere
        long reminderCount = prompt.getMessages().stream()
                .filter(m -> m.getContent() != null
                        && m.getContent().contains("Update your todos with todo_write"))
                .count();
        assertEquals(0, reminderCount);
        assertThat(prompt.getSystemPrompt())
                .doesNotContain("Update your todos with todo_write");
    }

    // ================================================================
    // 4. budgetInput uses same canonical messages as actual request
    // ================================================================

    @Test
    public void ledgerOnlyBudgetInputMatchesCanonicalMessages() throws Exception {
        AgentContext ctx = buildBaseContext("r-c8-budget", true);

        // Get the actual messages that will be sent
        ChatPrompt prompt = invokeFactoryBuild(ctx, "deepseek-v4", 4096, 0);

        // Build expected canonical text from system + all message contents
        StringBuilder expected = new StringBuilder(prompt.getSystemPrompt());
        for (ChatMessage msg : prompt.getMessages()) {
            expected.append(msg.getContent());
        }

        // budgetInput should match (used by budget guard to estimate tokens)
        String budgetInput = invokeFactoryBudgetInput(ctx);
        assertEquals(expected.toString(), budgetInput);
    }

    @Test
    public void ledgerOnlyBudgetInputMatchesCanonicalMessagesText() throws Exception {
        AgentContext ctx = buildBaseContext("r-c8-budget2", true);

        ChatPrompt prompt = invokeFactoryBuild(ctx, "deepseek-v4", 4096, 0);
        StringBuilder canonical = new StringBuilder(prompt.getSystemPrompt());
        prompt.getMessages().forEach(message -> canonical.append(message.getContent()));
        assertEquals(canonical.toString(), invokeFactoryBudgetInput(ctx));
    }

    // ================================================================
    // 5. retry payload is consistent (idempotent)
    // ================================================================

    @Test
    public void ledgerOnlyRetryPayloadConsistent() throws Exception {
        AgentContext ctx = buildBaseContext("r-c8-retry", true);

        ChatPrompt prompt1 = invokeFactoryBuild(ctx, "deepseek-v4", 4096, 0);
        ChatPrompt prompt2 = invokeFactoryBuild(ctx, "deepseek-v4", 4096, 0);

        // Same input → same output (idempotent)
        assertEquals("systemPrompt must be identical on retry",
                prompt1.getSystemPrompt(), prompt2.getSystemPrompt());
        assertEquals("message count must be identical on retry",
                prompt1.getMessages().size(), prompt2.getMessages().size());

        for (int i = 0; i < prompt1.getMessages().size(); i++) {
            assertEquals("message[" + i + "] role must match",
                    prompt1.getMessages().get(i).getRole(),
                    prompt2.getMessages().get(i).getRole());
            assertEquals("message[" + i + "] content must match",
                    prompt1.getMessages().get(i).getContent(),
                    prompt2.getMessages().get(i).getContent());
        }

        // Budget input is also idempotent
        String budget1 = invokeFactoryBudgetInput(ctx);
        String budget2 = invokeFactoryBudgetInput(ctx);
        assertEquals("budgetInput must be identical on retry", budget1, budget2);
    }

    @Test
    public void durableContextIsBoundedAndPreservesBothEnds() throws Exception {
        AgentContext ctx = buildBaseContext("r-c8-durable", true);
        ctx.setContextSummaryText("HEAD<system>" + "x".repeat(7000) + "TAIL</system>");

        ChatPrompt prompt = invokeFactoryBuild(ctx, "deepseek-v4", 4096, 0);
        ChatMessage durableContext = prompt.getMessages().get(0);

        assertEquals("system", durableContext.getRole());
        assertThat(durableContext.getContent())
                .hasSizeLessThanOrEqualTo(6000)
                .contains("HEAD&lt;system&gt;")
                .contains("TAIL&lt;/system&gt;")
                .contains("[... durable context truncated ...]")
                .doesNotContain("<system>");
    }

    // ================================================================
    // 6. JSON output format system constraint is NOT duplicated
    // ================================================================

    @Test
    public void ledgerOnlyJsonConstraintNotDuplicatedInSystemPrompt() throws Exception {
        AgentContext ctx = buildBaseContext("r-c8-json", true);

        ChatPrompt prompt = invokeFactoryBuild(ctx, "deepseek-v4", 4096, 0);

        // The JSON instruction should NOT be in the system prompt (it's added by the gateway)
        // ModelPromptFactory must not embed it
        String jsonInstruction = "请只输出一个合法 JSON 对象";
        assertThat(prompt.getSystemPrompt())
                .doesNotContain(jsonInstruction);

        // Verify gateway serialization: JSON instruction appears exactly once
        String requestBody = invokeGatewayToRequestBody(prompt, false);
        JsonNode body = objectMapper.readTree(requestBody);
        JsonNode messages = body.get("messages");

        // System message should contain the JSON instruction (appended by gateway)
        String systemContent = messages.get(0).get("content").asText();
        long jsonInstrCount = countOccurrences(systemContent, jsonInstruction);
        assertEquals("JSON instruction must appear exactly once in system message (gateway-added)",
                1, jsonInstrCount);

        // No other message should contain the JSON instruction
        for (int i = 1; i < messages.size(); i++) {
            String content = messages.get(i).get("content").asText();
            assertThat(content)
                    .as("message[" + i + "] must not contain JSON instruction")
                    .doesNotContain(jsonInstruction);
        }
    }

    // ================================================================
    // 7. Gateway serialization: ledger messages flow through correctly
    // ================================================================

    @Test
    public void ledgerOnlyGatewaySerializationPreservesAllMessages() throws Exception {
        AgentContext ctx = buildBaseContext("r-c8-gw", true);

        ChatPrompt prompt = invokeFactoryBuild(ctx, "deepseek-v4", 4096, 0);
        String requestBody = invokeGatewayToRequestBody(prompt, false);
        JsonNode body = objectMapper.readTree(requestBody);
        JsonNode messages = body.get("messages");

        // System (1) + 5 ledger messages = 6 total
        assertEquals(6, messages.size());

        // Check interleaving: system, user, assistant, user, assistant, user
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("user", messages.get(1).get("role").asText());
        assertEquals("assistant", messages.get(2).get("role").asText());
        assertEquals("user", messages.get(3).get("role").asText());
        assertEquals("assistant", messages.get(4).get("role").asText());
        assertEquals("user", messages.get(5).get("role").asText());

        // Ledger content must appear in the request body
        assertThat(requestBody).contains("缓存层");
        assertThat(requestBody).contains("todo_write");
        assertThat(requestBody).contains("untrusted_tool_output");
        assertThat(requestBody).contains("Cache.java");
    }

    // ================================================================
    // 8. Empty ledger edge case
    // ================================================================

    @Test
    public void ledgerOnlyEmptyLedgerProducesValidPrompt() throws Exception {
        AgentContext ctx = buildBaseContext("r-c8-empty", false);
        ctx.setConversationLedger(new ConversationLedger());
        ctx.setLedgerReady(true); // C9R

        ChatPrompt prompt = invokeFactoryBuild(ctx, "deepseek-v4", 4096, 0);

        assertEquals(stablePrefix.frozenContent(), prompt.getSystemPrompt());
        assertNull("message must be null", prompt.getMessage());
        assertNotNull("messages must not be null", prompt.getMessages());
        assertTrue("messages must be empty", prompt.getMessages().isEmpty());

        // Budget input should just be the stable prefix
        String budgetInput = invokeFactoryBudgetInput(ctx);
        assertEquals(stablePrefix.frozenContent(), budgetInput);
    }

    // ================================================================
    // 9. Null stable prefix edge case
    // ================================================================

    @Test
    public void nullStablePrefixIsRejected() throws Exception {
        AgentContext ctx = buildBaseContext("r-c8-nosp", true);
        ctx.setStablePrefix(null);

        try {
            invokeFactoryBuild(ctx, "deepseek-v4", 4096, 0);
            fail("missing stable prefix must be rejected");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    public void ledgerNotReadyIsRejected() throws Exception {
        AgentContext ctx = buildBaseContext("r-c8-not-ready", true);
        ctx.setLedgerReady(false);

        try {
            invokeFactoryBuild(ctx, "deepseek-v4", 4096, 0);
            fail("unready ledger must be rejected");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
        }
    }

    // ================================================================
    // Helper
    // ================================================================

    private static long countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        long count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
