package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.flow.node.ApprovalGateNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryApprovalStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ApprovalGateNodeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TOOL_NAME = "test_tool";

    // ==================== HIGH_RISK_DENY hard floor ====================

    @Test
    public void highRiskDenyShouldStayDeniedInAllModes() {
        for (String mode : new String[]{"SANDBOX", "ACCEPT_EDITS", "BYPASS"}) {
            AgentRuntimeProperties props = properties(mode, "CONFIRM");
            ApprovalGateNode node = gateNode(fixedPolicy(ToolPermissionLevel.HIGH_RISK_DENY), props);

            NodeResult result = node.apply(basicContext(TOOL_NAME));

            assertNotNull(result);
            assertEquals("HIGH_RISK_DENY should replan without executing in mode=" + mode,
                    AgentNodeNames.REPLAN_GUARD, result.getNextNode());
            assertTrue("HIGH_RISK_DENY should emit POLICY_DENIED in mode=" + mode,
                    result.getEvents().stream().anyMatch(e -> e.getType() == AgentEventType.POLICY_DENIED));
        }
    }

    // ==================== READ_ONLY auto-bypass ====================

    @Test
    public void readOnlyShouldBypassInAllModes() {
        for (String mode : new String[]{"SANDBOX", "ACCEPT_EDITS", "BYPASS"}) {
            AgentRuntimeProperties props = properties(mode, "CONFIRM");
            ApprovalGateNode node = gateNode(fixedPolicy(ToolPermissionLevel.READ_ONLY), props);

            NodeResult result = node.apply(basicContext(TOOL_NAME));

            assertNotNull(result);
            assertEquals("READ_ONLY should bypass to TOOL_DISPATCH in mode=" + mode,
                    AgentNodeNames.TOOL_DISPATCH, result.getNextNode());
        }
    }

    // ==================== WRITE_CONFIRM mode routing ====================

    @Test
    public void writeConfirmInSandboxShouldRequireApproval() {
        AgentRuntimeProperties props = properties("SANDBOX", "CONFIRM");
        ApprovalGateNode node = gateNode(fixedPolicy(ToolPermissionLevel.WRITE_CONFIRM), props);

        NodeResult result = node.apply(basicContext(TOOL_NAME));

        assertNotNull(result);
        assertTrue("WRITE_CONFIRM in SANDBOX should require approval (terminal)",
                result.isTerminal());
    }

    @Test
    public void writeConfirmInAcceptEditsShouldBypass() {
        AgentRuntimeProperties props = properties("ACCEPT_EDITS", "CONFIRM");
        ApprovalGateNode node = gateNode(fixedPolicy(ToolPermissionLevel.WRITE_CONFIRM), props);

        NodeResult result = node.apply(basicContext(TOOL_NAME));

        assertNotNull(result);
        assertEquals("WRITE_CONFIRM in ACCEPT_EDITS should bypass to TOOL_DISPATCH",
                AgentNodeNames.TOOL_DISPATCH, result.getNextNode());
    }

    @Test
    public void writeConfirmInBypassShouldBypass() {
        AgentRuntimeProperties props = properties("BYPASS", "CONFIRM");
        ApprovalGateNode node = gateNode(fixedPolicy(ToolPermissionLevel.WRITE_CONFIRM), props);

        NodeResult result = node.apply(basicContext(TOOL_NAME));

        assertNotNull(result);
        assertEquals("WRITE_CONFIRM in BYPASS should bypass to TOOL_DISPATCH",
                AgentNodeNames.TOOL_DISPATCH, result.getNextNode());
    }

    // ==================== HIGH_RISK_CONFIRM mode routing ====================

    @Test
    public void highRiskConfirmInSandboxWithConfirmPolicyShouldRequireApproval() {
        AgentRuntimeProperties props = properties("SANDBOX", "CONFIRM");
        ApprovalGateNode node = gateNode(fixedPolicy(ToolPermissionLevel.HIGH_RISK_CONFIRM), props);

        NodeResult result = node.apply(basicContext(TOOL_NAME));

        assertNotNull(result);
        assertTrue("HIGH_RISK_CONFIRM in SANDBOX with CONFIRM policy should require approval",
                result.isTerminal());
    }

    @Test
    public void highRiskConfirmInSandboxWithDenyPolicyShouldDeny() {
        AgentRuntimeProperties props = properties("SANDBOX", "DENY");
        ApprovalGateNode node = gateNode(fixedPolicy(ToolPermissionLevel.HIGH_RISK_CONFIRM), props);

        NodeResult result = node.apply(basicContext(TOOL_NAME));

        assertNotNull(result);
        assertEquals("HIGH_RISK_CONFIRM in SANDBOX with DENY policy should replan",
                AgentNodeNames.REPLAN_GUARD, result.getNextNode());
        assertTrue("should contain POLICY_DENIED events",
                result.getEvents().stream().anyMatch(e -> e.getType() == AgentEventType.POLICY_DENIED));
    }

    @Test
    public void highRiskConfirmInSandboxWithAllowPolicyShouldBypass() {
        AgentRuntimeProperties props = properties("SANDBOX", "ALLOW");
        ApprovalGateNode node = gateNode(fixedPolicy(ToolPermissionLevel.HIGH_RISK_CONFIRM), props);

        NodeResult result = node.apply(basicContext(TOOL_NAME));

        assertNotNull(result);
        assertEquals("HIGH_RISK_CONFIRM in SANDBOX with ALLOW should bypass",
                AgentNodeNames.TOOL_DISPATCH, result.getNextNode());
    }

    @Test
    public void highRiskConfirmInAcceptEditsShouldFollowHighRiskPolicy() {
        // ACCEPT_EDITS only auto-bypasses WRITE_CONFIRM; HIGH_RISK_CONFIRM still follows highRiskPolicy
        AgentRuntimeProperties props = properties("ACCEPT_EDITS", "CONFIRM");
        ApprovalGateNode node = gateNode(fixedPolicy(ToolPermissionLevel.HIGH_RISK_CONFIRM), props);

        NodeResult result = node.apply(basicContext(TOOL_NAME));

        assertNotNull(result);
        assertTrue("HIGH_RISK_CONFIRM in ACCEPT_EDITS with CONFIRM should still require approval",
                result.isTerminal());
    }

    @Test
    public void highRiskConfirmInAcceptEditsWithAllowPolicyShouldBypass() {
        AgentRuntimeProperties props = properties("ACCEPT_EDITS", "ALLOW");
        ApprovalGateNode node = gateNode(fixedPolicy(ToolPermissionLevel.HIGH_RISK_CONFIRM), props);

        NodeResult result = node.apply(basicContext(TOOL_NAME));

        assertNotNull(result);
        assertEquals("HIGH_RISK_CONFIRM in ACCEPT_EDITS with ALLOW should bypass",
                AgentNodeNames.TOOL_DISPATCH, result.getNextNode());
    }

    @Test
    public void highRiskConfirmInBypassShouldBypass() {
        AgentRuntimeProperties props = properties("BYPASS", "CONFIRM");
        ApprovalGateNode node = gateNode(fixedPolicy(ToolPermissionLevel.HIGH_RISK_CONFIRM), props);

        NodeResult result = node.apply(basicContext(TOOL_NAME));

        assertNotNull(result);
        assertEquals("HIGH_RISK_CONFIRM in BYPASS should bypass",
                AgentNodeNames.TOOL_DISPATCH, result.getNextNode());
    }

    @Test
    public void highRiskConfirmInBypassShouldBypassEvenWithDenyPolicy() {
        // BYPASS overrides highRiskPolicy for HIGH_RISK_CONFIRM
        AgentRuntimeProperties props = properties("BYPASS", "DENY");
        ApprovalGateNode node = gateNode(fixedPolicy(ToolPermissionLevel.HIGH_RISK_CONFIRM), props);

        NodeResult result = node.apply(basicContext(TOOL_NAME));

        assertNotNull(result);
        assertEquals("HIGH_RISK_CONFIRM in BYPASS should bypass regardless of highRiskPolicy",
                AgentNodeNames.TOOL_DISPATCH, result.getNextNode());
    }

    // ==================== null / default handling ====================

    @Test
    public void nullPermissionModeShouldDefaultToSandbox() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.setPermissionMode(null);
        props.setHighRiskPolicy("CONFIRM");
        ApprovalGateNode node = gateNode(fixedPolicy(ToolPermissionLevel.WRITE_CONFIRM), props);

        NodeResult result = node.apply(basicContext(TOOL_NAME));

        assertNotNull(result);
        assertTrue("null permissionMode should behave as SANDBOX (require approval)",
                result.isTerminal());
    }

    // ==================== helpers ====================

    private ApprovalGateNode gateNode(AgentTool tool, AgentRuntimeProperties props) {
        ToolRegistry registry = new ToolRegistry(List.of(tool), new ToolSchemaValidator(new ObjectMapper()));
        return new ApprovalGateNode(registry, new InMemoryApprovalStore(), props);
    }

    private AgentTool fixedPolicy(ToolPermissionLevel level) {
        return new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder().name(TOOL_NAME).description("test")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"p\":{\"type\":\"string\"}},\"additionalProperties\":false}")
                        .build();
            }

            @Override
            public ToolPolicyDecision policy(ToolCall call) {
                return switch (level) {
                    case READ_ONLY -> ToolPolicyDecision.readOnly("test read-only", TOOL_NAME);
                    case WRITE_CONFIRM -> ToolPolicyDecision.writeConfirm("test write", TOOL_NAME);
                    case HIGH_RISK_CONFIRM -> ToolPolicyDecision.highRiskConfirm("test high risk", TOOL_NAME);
                    case HIGH_RISK_DENY -> ToolPolicyDecision.highRiskDeny("test deny", TOOL_NAME);
                    case PERSISTENT_STATE_WRITE -> ToolPolicyDecision.writeConfirm("test persistent state", TOOL_NAME);
                };
            }

            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.success("ok", false, 1L);
            }
        };
    }

    private AgentContext basicContext(String toolName) {
        AgentContext context = new AgentContext();
        context.setRunId("test-run-" + UUID.randomUUID());
        context.setRootRunId(context.getRunId());
        context.setRequestId("req-1");
        context.setConversationId("conv-1");
        context.setQuestion("test question");
        context.setStep(1);
        context.setStartedAt(Instant.now());
        context.setCurrentNode(AgentNodeNames.APPROVAL_GATE);
        context.setCurrentSpanId("span-1");
        context.setWorkspaceDisplayName("test");
        context.setDecision(AgentDecision.builder()
                .tool(toolName)
                .input(new ObjectMapper().createObjectNode().put("cmd", "test"))
                .build());
        return context;
    }

    private AgentRuntimeProperties properties(String permissionMode, String highRiskPolicy) {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.setPermissionMode(permissionMode);
        props.setHighRiskPolicy(highRiskPolicy);
        props.setApprovalTtlSeconds(900L);
        return props;
    }
}
