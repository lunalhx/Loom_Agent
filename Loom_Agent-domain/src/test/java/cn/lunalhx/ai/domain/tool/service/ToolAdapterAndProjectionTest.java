package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.EffectProfile;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.OutboundDisclosure;
import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.PermissionDecision;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolEffect;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.model.NormalizedToolCall;
import cn.lunalhx.ai.domain.tool.model.PermissionSubject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Adapter invoke and deterministic projection are separately testable seams. */
public class ToolAdapterAndProjectionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"],\"additionalProperties\":false}";

    @Test
    public void adapterInvokeDoesNotProjectIntoAgentContext() {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = registry(calls, "RAW secret-output");
        ToolAdapterInvoker invoker = new ToolAdapterInvoker(registry);
        AgentContext context = context();
        AuthorizedToolCall authorized = authorized();

        ToolResult raw = invoker.invoke(authorized);

        assertEquals(1, calls.get());
        assertEquals("RAW secret-output", raw.getObservation());
        assertEquals(null, context.getToolResult());
    }

    @Test
    public void projectorTurnsAdapterResultIntoAgentVisibleResult() {
        ToolResultProjector projector = new ToolResultProjector(null);
        AgentContext context = context();
        AuthorizedToolCall authorized = authorized();
        ToolResult raw = ToolResult.builder()
                .success(true)
                .observation("RAW secret-output")
                .elapsedMs(12L)
                .build();

        ToolResult projected = projector.project(context, authorized, raw, null);

        assertEquals("RAW secret-output", projected.getObservation());
        assertEquals("ok", projected.getToolStatus());
        assertEquals(Boolean.FALSE, projected.getWorkspaceChanged());
        assertEquals(projected, context.getToolResult());
    }

    private AuthorizedToolCall authorized() {
        ToolCall call = ToolCall.builder().name("read_file")
                .input(JsonNodeFactory.instance.objectNode().put("path", "a.txt"))
                .build();
        call.setExecutionProfile(ExecutionProfile.forRun(CollaborationMode.BUILD, false));
        NormalizedToolCall normalized = new ToolCallNormalizer(mapper).normalize(call);
        EffectProfile effect = new EffectProfile(
                Set.of(ToolEffect.REPOSITORY_READ), OutboundDisclosure.NONE, true);
        PermissionDecision decision = new PermissionDecision(
                PermissionAction.ALLOW, "allow", List.of(), List.of(), false);
        return new AuthorizedToolCall(call, normalized, effect, call.getExecutionProfile(),
                call.getExecutionProfile(), decision, "run-1", "digest");
    }

    private AgentContext context() {
        AgentContext context = new AgentContext();
        context.setRunId("run-1");
        context.setCollaborationMode(CollaborationMode.BUILD);
        context.setExecutionProfile(ExecutionProfile.forRun(CollaborationMode.BUILD, false));
        context.setPermissionPolicySnapshot(new PermissionPolicySnapshot(PermissionAction.ALLOW, List.of(), List.of()));
        context.setHistory(new java.util.ArrayList<>());
        return context;
    }

    private ToolRegistry registry(AtomicInteger calls, String observation) {
        return new ToolRegistry(List.of(new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder().name("read_file").description("read")
                        .inputSchema(SCHEMA)
                        .capabilityEnvelope(ToolCapabilityEnvelope.repositoryRead())
                        .build();
            }

            @Override
            public ToolResult call(ToolCall call) {
                calls.incrementAndGet();
                return ToolResult.success(observation, false, 0L);
            }
        }), new ToolSchemaValidator(mapper));
    }
}
