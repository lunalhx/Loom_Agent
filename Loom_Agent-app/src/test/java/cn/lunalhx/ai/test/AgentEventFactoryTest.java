package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.model.valobj.WorkspaceResolutionException;
import cn.lunalhx.ai.domain.agent.service.execution.AgentEventFactory;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

public class AgentEventFactoryTest {

    private final AgentEventFactory factory = new AgentEventFactory();

    @Test
    public void nodeStartedShouldContainNodeInfoAndContextIds() {
        AgentContext context = basicContext("run-1", "request-1", "conversation-1");
        AgentNode node = new TestNode("model_call", List.of("prompt", "specs"));

        AgentEvent event = factory.nodeStarted(context, node);

        assertEquals(AgentEventType.NODE_START, event.getType());
        assertEquals("run-1", event.getRunId());
        assertEquals("request-1", event.getRequestId());
        assertEquals("conversation-1", event.getConversationId());
        assertEquals("model_call", event.getNode());
        assertEquals(List.of("prompt", "specs"), event.getNodeInputs());
    }

    @Test
    public void resumeStartedShouldContainCheckpointVersionAndPlan() {
        AgentContext context = basicContext("run-2", "request-2", "conversation-2");
        context.setCheckpointVersion(42L);
        context.setPlan(new AgentPlan());

        AgentEvent event = factory.resumeStarted(context);

        assertEquals(AgentEventType.RESUME_STARTED, event.getType());
        assertEquals("run-2", event.getRunId());
        assertEquals(Long.valueOf(42L), event.getCheckpointVersion());
        assertNotNull(event.getPlan());
    }

    @Test
    public void approvalRequiredShouldContainApprovalMetadata() {
        AgentContext context = basicContext("run-3", "request-3", "conversation-3");
        context.setStep(5);
        PendingApproval approval = PendingApproval.builder()
                .approvalId("approval-1")
                .workspaceDisplayName("project-a")
                .tool("write_file")
                .input(Map.of("path", "Demo.java"))
                .permissionLevel(ToolPermissionLevel.WRITE_CONFIRM)
                .riskReason("写入文件")
                .operationPreview("创建 Demo.java")
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AgentEvent event = factory.approvalRequired(context, approval);

        assertEquals(AgentEventType.APPROVAL_REQUIRED, event.getType());
        assertEquals("approval-1", event.getApprovalId());
        assertEquals("project-a", event.getWorkspace());
        assertEquals("write_file", event.getTool());
        assertEquals(Integer.valueOf(6), event.getStep());
        assertEquals("WRITE_CONFIRM", event.getPermissionLevel());
        assertEquals("写入文件", event.getRiskReason());
        assertEquals("创建 Demo.java", event.getOperationPreview());
        assertNotNull(event.getExpiresAt());
    }

    @Test
    public void userInputRequiredShouldContainRecoveryMetadata() {
        AgentContext context = basicContext("run-4", "request-4", "conversation-4");
        context.setContextRecoveryStage(ContextRecoveryStage.WAITING_USER_INPUT);
        context.setContextTranscriptArtifactId("tx-artifact");
        context.setContextBlockedReason("oversize");

        AgentEvent event = factory.userInputRequired(context);

        assertEquals(AgentEventType.USER_INPUT_REQUIRED, event.getType());
        assertEquals(ModelErrorCode.CONTEXT_OVERFLOW.code(), event.getCode());
        assertEquals("run-4", event.getRunId());
        assertNotNull(event.getMetadata());
        assertEquals(List.of("CONTINUE", "ABORT"), event.getMetadata().get("allowedActions"));
        assertEquals("WAITING_USER_INPUT", event.getMetadata().get("recoveryStage"));
        assertEquals("tx-artifact", event.getMetadata().get("transcriptArtifactId"));
        assertEquals("oversize", event.getMetadata().get("blockedReason"));
    }

    @Test
    public void workspaceErrorShouldNotLeakExtraInfo() {
        WorkspaceResolutionException ex = new WorkspaceResolutionException("WORKSPACE_NOT_FOUND", "工作区不存在：/bad");

        AgentEvent event = factory.workspaceError(ex);

        assertEquals(AgentEventType.ERROR, event.getType());
        assertEquals("WORKSPACE_NOT_FOUND", event.getCode());
        assertEquals("工作区不存在：/bad", event.getMessage());
        assertNotNull(event.getRequestId());
    }

    @Test
    public void agentErrorShouldHaveStableCode() {
        AgentEvent event = factory.agentError();

        assertEquals(AgentEventType.ERROR, event.getType());
        assertEquals("agent_error", event.getCode());
        assertEquals("Agent 执行失败", event.getMessage());
    }

    @Test
    public void approvalNotFoundShouldContainApprovalId() {
        AgentEvent event = factory.approvalNotFound("missing-id");

        assertEquals(AgentEventType.ERROR, event.getType());
        assertEquals("approval_not_found", event.getCode());
        assertEquals("missing-id", event.getApprovalId());
        assertEquals("审批不存在或已过期", event.getMessage());
    }

    @Test
    public void checkpointNotFoundShouldContainRunId() {
        AgentEvent event = factory.checkpointNotFound("no-checkpoint-run");

        assertEquals(AgentEventType.ERROR, event.getType());
        assertEquals("checkpoint_not_found", event.getCode());
        assertEquals("no-checkpoint-run", event.getRunId());
    }

    @Test
    public void runNotWaitingUserInputShouldContainRunId() {
        AgentEvent event = factory.runNotWaitingUserInput("other-run");

        assertEquals(AgentEventType.ERROR, event.getType());
        assertEquals("run_not_waiting_user_input", event.getCode());
        assertEquals("other-run", event.getRunId());
    }

    @Test
    public void invalidUserInputShouldContainRunId() {
        AgentEvent event = factory.invalidUserInput("input-run");

        assertEquals(AgentEventType.ERROR, event.getType());
        assertEquals("invalid_user_input", event.getCode());
        assertEquals("input-run", event.getRunId());
    }

    @Test
    public void runAlreadyTerminalShouldContainStatusInMetadata() {
        AgentRun run = AgentRun.builder()
                .runId("run-terminal")
                .requestId("request-terminal")
                .conversationId("conversation-terminal")
                .workspace("test-workspace")
                .parentRunId("parent-terminal")
                .status(AgentRunStatus.COMPLETED)
                .build();

        AgentEvent event = factory.runAlreadyTerminal(run);

        assertEquals(AgentEventType.ERROR, event.getType());
        assertEquals("run_already_terminal", event.getCode());
        assertEquals("当前运行已结束，不能再次恢复", event.getMessage());
        assertEquals("run-terminal", event.getRunId());
        assertEquals("request-terminal", event.getRequestId());
        assertEquals("conversation-terminal", event.getConversationId());
        assertEquals("test-workspace", event.getWorkspace());
        assertEquals("parent-terminal", event.getParentRunId());
        assertNotNull(event.getMetadata());
        assertEquals("COMPLETED", event.getMetadata().get("status"));
    }

    private AgentContext basicContext(String runId, String requestId, String conversationId) {
        AgentContext context = new AgentContext();
        context.setRunId(runId);
        context.setRequestId(requestId);
        context.setConversationId(conversationId);
        context.setWorkspaceDisplayName("test-workspace");
        context.setParentRunId("parent-run");
        return context;
    }

    private static class TestNode implements AgentNode {
        private final String name;
        private final List<String> inputKeys;

        TestNode(String name, List<String> inputKeys) {
            this.name = name;
            this.inputKeys = inputKeys;
        }

        @Override
        public String name() { return name; }

        @Override
        public List<String> inputKeys() { return inputKeys; }

        @Override
        public NodeResult apply(AgentContext context) { return NodeResult.terminal(List.of()); }
    }
}
