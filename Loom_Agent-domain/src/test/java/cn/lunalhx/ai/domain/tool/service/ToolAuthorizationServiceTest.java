package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.GrantLifetime;
import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Public authorization seam: decisions only, no interactive I/O. */
public class ToolAuthorizationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String WRITE_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},"
                    + "\"required\":[\"path\",\"content\"],\"additionalProperties\":false}";

    @Test
    public void askDecisionReturnsApprovalRequiredWithoutPrompting() {
        AtomicInteger prompts = new AtomicInteger();
        ToolAuthorizationService service = new ToolAuthorizationService(
                registry(), mapper);
        AgentContext context = context(PermissionAction.ASK);

        ToolAuthorizationResult result = service.authorize(
                context,
                ToolCall.builder().name("write_file")
                        .input(JsonNodeFactory.instance.objectNode()
                                .put("path", "a.txt").put("content", "x"))
                        .build(),
                policy(context),
                context.getPermissionPolicySnapshot());

        assertTrue(result.needsApproval());
        assertFalse(result.authorized());
        assertNotNull(result.pendingApproval());
        assertEquals(PermissionAction.ASK, result.pendingApproval().decision().action());
        assertEquals(0, prompts.get());
    }

    @Test
    public void planModeDeniesNonVisibleToolBeforeAllowlistRejection() {
        ToolAuthorizationService service = new ToolAuthorizationService(registry(), mapper);
        AgentContext context = context(PermissionAction.ALLOW);
        context.setCollaborationMode(CollaborationMode.PLAN);
        context.setExecutionProfile(ExecutionProfile.forRun(CollaborationMode.PLAN, false));

        ToolAuthorizationResult result = service.authorize(
                context,
                ToolCall.builder().name("write_file")
                        .input(JsonNodeFactory.instance.objectNode()
                                .put("path", "a.txt").put("content", "x"))
                        .build(),
                new ToolExecutor.ToolRuntimePolicy(
                        Set.of("list_files"), CollaborationMode.PLAN, 0, 1, context.getExecutionProfile()),
                context.getPermissionPolicySnapshot());

        assertTrue(result.rejection() != null);
        assertEquals("plan_mode_denied", result.rejection().getErrorCode());
    }

    @Test
    public void completingApprovalMintsAuthorizedCallWithoutRePrompting() {
        ToolAuthorizationService service = new ToolAuthorizationService(registry(), mapper);
        AgentContext context = context(PermissionAction.ASK);
        ToolAuthorizationResult pending = service.authorize(
                context,
                ToolCall.builder().name("write_file")
                        .input(JsonNodeFactory.instance.objectNode()
                                .put("path", "a.txt").put("content", "x"))
                        .build(),
                policy(context),
                context.getPermissionPolicySnapshot());

        ToolAuthorizationResult completed = service.completeApproval(
                context, pending.pendingApproval(), GrantLifetime.ONCE);

        assertTrue(completed.authorized());
        assertEquals("write_file", completed.authorizedCall().toolName());
        assertTrue(context.getPermissionGrants().isEmpty());
    }

    private AgentContext context(PermissionAction defaultAction) {
        AgentContext context = new AgentContext();
        context.setRunId("auth-test");
        context.setHistory(new java.util.ArrayList<>());
        context.setCollaborationMode(CollaborationMode.BUILD);
        context.setExecutionProfile(ExecutionProfile.forRun(CollaborationMode.BUILD, false));
        context.setPermissionPolicySnapshot(new PermissionPolicySnapshot(defaultAction, List.of(), List.of()));
        return context;
    }

    private ToolExecutor.ToolRuntimePolicy policy(AgentContext context) {
        return new ToolExecutor.ToolRuntimePolicy(
                Set.of("write_file"), CollaborationMode.BUILD, 0, 1, context.getExecutionProfile());
    }

    private ToolRegistry registry() {
        return new ToolRegistry(List.of(new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder().name("write_file").description("write")
                        .inputSchema(WRITE_SCHEMA)
                        .capabilityEnvelope(ToolCapabilityEnvelope.repositoryMutation())
                        .build();
            }

            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.success("ok", false, 0L);
            }
        }), new ToolSchemaValidator(mapper));
    }
}
