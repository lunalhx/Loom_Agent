package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import cn.lunalhx.ai.domain.agent.service.observability.ModelCallTraceLabels;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * ModelCallTraceLabels 是 trace metadata 的唯一来源：
 * 五个低基数标签（model / capability / purpose / node / agentRole）必须完整且仅当真实存在时填充，
 * 不能引入 runId / requestId / conversationId / 用户输入等高基数字段。
 */
public class ModelCallTraceLabelsTest {

    @Test
    public void buildUsageMetadataShouldIncludeAllFiveLabels() {
        AgentContext context = new AgentContext();
        context.setAgentRole(AgentRole.EXPLORER);
        context.setRunId("run-1");
        context.setRootRunId("root-1");

        Map<String, Object> metadata = ModelCallTraceLabels.buildUsageMetadata(
                context, "model_call", "complete.agent_decision",
                ModelCallPurpose.CONTROL_JSON, "deepseek-v4-flash",
                TokenUsage.builder().promptTokens(10).build(), null);

        assertEquals("model_call", metadata.get("node"));
        assertEquals("complete.agent_decision", metadata.get("capability"));
        assertEquals("CONTROL_JSON", metadata.get("purpose"));
        assertEquals("deepseek-v4-flash", metadata.get("model"));
        assertEquals("EXPLORER", metadata.get("agentRole"));
        assertFalse("usage 非 null 时不应出现 usageMissing 标记", metadata.containsKey("usageMissing"));
    }

    @Test
    public void buildUsageMetadataShouldMarkUsageMissingWhenNull() {
        Map<String, Object> metadata = ModelCallTraceLabels.buildUsageMetadata(
                null, "replan", "complete.replan",
                ModelCallPurpose.CONTROL_JSON, null, null, null);

        assertEquals("replan", metadata.get("node"));
        assertEquals("complete.replan", metadata.get("capability"));
        assertEquals("CONTROL_JSON", metadata.get("purpose"));
        // 缺省值都用 "none"，绝不抛 NPE
        assertEquals("none", metadata.get("model"));
        assertEquals("none", metadata.get("agentRole"));
        assertEquals(Boolean.TRUE, metadata.get("usageMissing"));
    }

    @Test
    public void buildUsageMetadataShouldEncodeSubAgentInAgentRole() {
        AgentContext subContext = new AgentContext();
        subContext.setAgentRole(AgentRole.REVIEWER);
        subContext.setParentRunId("parent-run");

        Map<String, Object> metadata = ModelCallTraceLabels.buildUsageMetadata(
                subContext, "sub_agent_dispatch", "complete.agent_decision",
                ModelCallPurpose.CONTROL_JSON, "deepseek-v4-flash", null, null);

        // 用单一 agent_role 标签同时表达 role + sub/root，避免引入第二个高基数字段
        assertEquals("REVIEWER.sub", metadata.get("agentRole"));
    }

    @Test
    public void buildUsageMetadataShouldMergeExtrasWithoutOverwritingLabels() {
        Map<String, Object> metadata = ModelCallTraceLabels.buildUsageMetadata(
                null, "context_summary", "complete.context_summary",
                ModelCallPurpose.CONTEXT_SUMMARY, "deepseek-v4-flash",
                TokenUsage.builder().build(),
                Map.of("finishReason", "stop", "inputChars", 12345));

        assertEquals("context_summary", metadata.get("node"));
        assertEquals("stop", metadata.get("finishReason"));
        assertEquals(12345, metadata.get("inputChars"));
        // extras 不会覆盖我们设置的 5 个核心标签
        assertEquals("CONTEXT_SUMMARY", metadata.get("purpose"));
    }

    @Test
    public void buildUsageMetadataShouldHandleNullPurposeAsNone() {
        Map<String, Object> metadata = ModelCallTraceLabels.buildUsageMetadata(
                null, "model_call", "complete.agent_decision",
                null, "deepseek-v4-flash", null, null);

        assertEquals("none", metadata.get("purpose"));
    }

    @Test
    public void agentRoleTagShouldBeNoneWhenContextIsNull() {
        assertEquals("none", ModelCallTraceLabels.agentRoleTag(null));
    }

    @Test
    public void agentRoleTagShouldDistinguishRootAndSub() {
        AgentContext root = new AgentContext();
        root.setAgentRole(AgentRole.EDITOR);
        assertEquals("EDITOR", ModelCallTraceLabels.agentRoleTag(root));

        AgentContext sub = new AgentContext();
        sub.setAgentRole(AgentRole.EDITOR);
        sub.setParentRunId("parent-1");
        assertEquals("EDITOR.sub", ModelCallTraceLabels.agentRoleTag(sub));
    }

    @Test
    public void agentRoleTagShouldHandleNullRole() {
        AgentContext context = new AgentContext();
        assertEquals("none", ModelCallTraceLabels.agentRoleTag(context));
    }

    @Test
    public void buildUsageMetadataShouldNotContainHighCardinalityKeys() {
        AgentContext context = new AgentContext();
        context.setRunId("run-1");
        context.setRequestId("req-1");
        context.setConversationId("conv-1");
        context.setAgentRole(AgentRole.EXPLORER);

        Map<String, Object> metadata = ModelCallTraceLabels.buildUsageMetadata(
                context, "model_call", "complete.agent_decision",
                ModelCallPurpose.CONTROL_JSON, "deepseek-v4-flash", null, null);

        for (String forbidden : new String[]{"runId", "traceId", "requestId",
                "conversationId", "workspace", "userId", "question"}) {
            assertNull("metadata 不应包含高基数 key: " + forbidden, metadata.get(forbidden));
        }
        assertTrue("agentRole 应被保留", metadata.containsKey("agentRole"));
    }
}
