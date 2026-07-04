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
    public void buildUsageMetadataShouldEncodeSubAgentInAgentRole() {
        AgentContext subContext = new AgentContext();
        subContext.setAgentRole(AgentRole.REVIEWER);
        subContext.setParentRunId("parent-run");

        Map<String, Object> metadata = ModelCallTraceLabels.buildUsageMetadata(
                subContext, "sub_agent_dispatch", "complete.agent_decision",
                ModelCallPurpose.CONTROL_JSON, "deepseek-v4-flash", null, null);

        assertEquals("REVIEWER.sub", metadata.get("agentRole"));
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
