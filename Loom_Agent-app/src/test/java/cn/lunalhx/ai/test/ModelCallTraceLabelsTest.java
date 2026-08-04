package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.service.observability.ModelCallTraceLabels;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ModelCallTraceLabelsTest {

    @Test
    public void agentRoleTagShouldDistinguishRootAndDelegate() {
        AgentContext root = new AgentContext();
        assertEquals("root", ModelCallTraceLabels.agentRoleTag(root));

        AgentContext sub = new AgentContext();
        sub.setParentRunId("parent-1");
        assertEquals("delegate", ModelCallTraceLabels.agentRoleTag(sub));
    }

    @Test
    public void buildUsageMetadataShouldEncodeDelegateInAgentRole() {
        AgentContext subContext = new AgentContext();
        subContext.setParentRunId("parent-run");

        Map<String, Object> metadata = ModelCallTraceLabels.buildUsageMetadata(
                subContext, "model_call", "complete.agent_decision",
                ModelCallPurpose.CONTROL_JSON, "deepseek-v4-flash", null, null);

        assertEquals("delegate", metadata.get("agentRole"));
    }

    @Test
    public void buildUsageMetadataShouldNotContainHighCardinalityKeys() {
        AgentContext context = new AgentContext();
        context.setRunId("run-1");
        context.setRequestId("req-1");
        context.setConversationId("conv-1");

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
