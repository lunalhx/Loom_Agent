package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.service.prompt.StablePrefixBuilder;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ApprovalRequirement;
import cn.lunalhx.ai.domain.tool.model.CallEffectAssessment;
import cn.lunalhx.ai.domain.tool.model.EffectProfile;
import cn.lunalhx.ai.domain.tool.model.OutboundDisclosure;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolEffect;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.ToolExecutor;
import cn.lunalhx.ai.domain.tool.service.ToolInputGate;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PlanModeBoundaryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

    @Test
    public void planCatalogContainsOnlyCapabilitiesWithValidPlanInvocations() {
        ToolRegistry registry = registry(new AtomicInteger());

        List<String> names = registry.effectiveSpecs(CollaborationMode.PLAN).stream()
                .map(ToolSpec::getName)
                .toList();

        assertEquals(List.of("list_files", "read_file", "search", "delegate"), names);
        assertFalse(names.contains("run_shell"));
        assertFalse(names.contains("write_file"));
        assertFalse(names.contains("patch_file"));
    }

    @Test
    public void planPromptDoesNotAdvertiseHiddenMutationOrShellTools() {
        ToolRegistry registry = registry(new AtomicInteger());
        String prompt = new StablePrefixBuilder()
                .build(false, true, null, registry.effectiveSpecs(CollaborationMode.PLAN),
                        "", null, CollaborationMode.PLAN, null)
                .frozenContent();

        assertTrue(prompt.contains("Collaboration mode: plan"));
        assertFalse(prompt.contains("run_shell"));
        assertFalse(prompt.contains("write_file"));
        assertFalse(prompt.contains("patch_file"));
    }

    @Test
    public void dynamicToolWithAReadOnlyInvocationRemainsVisible() {
        AgentTool dynamic = new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder()
                        .name("dynamic_remote")
                        .description("dynamic remote tool")
                        .inputSchema(SCHEMA)
                        .capabilityEnvelope(ToolCapabilityEnvelope.shell())
                        .build();
            }

            @Override
            public CallEffectAssessment assessEffect(ToolCall call) {
                return CallEffectAssessment.trusted(new EffectProfile(
                        Set.of(ToolEffect.EXTERNAL_READ), OutboundDisclosure.NONE, true));
            }

            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.success("ok", false, 0L);
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(dynamic), new ToolSchemaValidator(mapper));

        assertEquals(List.of("dynamic_remote"), registry.effectiveSpecs(CollaborationMode.PLAN)
                .stream().map(ToolSpec::getName).toList());
    }

    @Test
    public void planDeniesMutationBeforeApprovalAndExecution() {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = registry(calls);
        ToolInputGate gate = new ToolInputGate(registry, (name, args) -> {
            throw new AssertionError("Plan denial must not ask for approval");
        });
        AgentContext context = new AgentContext();
        context.setCollaborationMode(CollaborationMode.PLAN);
        context.setHistory(new ArrayList<>());

        ToolResult result = gate.evaluate(context,
                ToolCall.builder().name("write_file").input(mapper.createObjectNode()).build(),
                ToolExecutor.ToolRuntimePolicy.root(Set.of("write_file"),
                        CollaborationMode.PLAN, ToolExecutor.ApprovalPolicy.AUTO));

        assertTrue(result != null);
        assertEquals("plan_mode_denied", result.getErrorCode());
        assertEquals(0, calls.get());
    }

    @Test
    public void planRejectsHiddenStaleAndFabricatedMutationCallsBeforeApproval() {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = registry(calls);
        ToolInputGate gate = new ToolInputGate(registry, (name, args) -> {
            throw new AssertionError("Plan denial must not ask for approval");
        });
        AgentContext context = new AgentContext();
        context.setCollaborationMode(CollaborationMode.PLAN);
        context.setHistory(new ArrayList<>());

        for (String name : List.of("run_shell", "write_file", "patch_file", "stale_write_file")) {
            ToolResult result = gate.evaluate(context,
                    ToolCall.builder().name(name).input(mapper.createObjectNode()).build(),
                    ToolExecutor.ToolRuntimePolicy.root(Set.of(name),
                            CollaborationMode.PLAN, ToolExecutor.ApprovalPolicy.AUTO));
            assertEquals(name, "plan_mode_denied", result.getErrorCode());
        }
        assertEquals(0, calls.get());
    }

    @Test
    public void buildKeepsApprovedMutationUsable() {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = registry(calls);
        ToolInputGate gate = new ToolInputGate(registry, (name, args) -> true);
        AgentContext context = new AgentContext();
        context.setCollaborationMode(CollaborationMode.BUILD);
        context.setHistory(new ArrayList<>());

        ToolCall call = ToolCall.builder()
                .name("write_file")
                .input(mapper.createObjectNode())
                .build();
        ToolExecutor.ToolRuntimePolicy policy = ToolExecutor.ToolRuntimePolicy.root(
                Set.of("write_file"), CollaborationMode.BUILD, ToolExecutor.ApprovalPolicy.ASK);

        assertNull(gate.evaluate(context, call, policy));
        assertTrue(registry.call(call).isSuccess());
        assertEquals(1, calls.get());
    }

    private ToolRegistry registry(AtomicInteger calls) {
        return new ToolRegistry(List.of(
                tool("list_files", ToolCapabilityEnvelope.repositoryRead(), ApprovalRequirement.NONE, calls),
                tool("read_file", ToolCapabilityEnvelope.repositoryRead(), ApprovalRequirement.NONE, calls),
                tool("search", ToolCapabilityEnvelope.repositoryRead(), ApprovalRequirement.NONE, calls),
                tool("delegate", ToolCapabilityEnvelope.repositoryRead(), ApprovalRequirement.NONE, calls),
                tool("run_shell", ToolCapabilityEnvelope.shell(), ApprovalRequirement.SESSION_POLICY, calls),
                tool("write_file", ToolCapabilityEnvelope.repositoryMutation(), ApprovalRequirement.SESSION_POLICY, calls),
                tool("patch_file", ToolCapabilityEnvelope.repositoryMutation(), ApprovalRequirement.SESSION_POLICY, calls),
                tool("mcp_unknown", ToolCapabilityEnvelope.untrustedUnknown(), ApprovalRequirement.NONE, calls)),
                new ToolSchemaValidator(mapper));
    }

    private AgentTool tool(String name, ToolCapabilityEnvelope envelope,
                           ApprovalRequirement approval, AtomicInteger calls) {
        return new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder()
                        .name(name)
                        .description(name)
                        .inputSchema(SCHEMA)
                        .capabilityEnvelope(envelope)
                        .approvalRequirement(approval)
                        .build();
            }

            @Override
            public CallEffectAssessment assessEffect(ToolCall call) {
                return CallEffectAssessment.trusted(envelope.toEffectProfile());
            }

            @Override
            public ToolResult call(ToolCall call) {
                calls.incrementAndGet();
                return ToolResult.success("ok", false, 0L);
            }
        };
    }
}
