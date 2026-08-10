package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ApprovalRequirement;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ToolRegistryTest {

    private static final String VALID_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"p\":{\"type\":\"string\"}},\"additionalProperties\":false}";

    private final ObjectMapper mapper = new ObjectMapper();

    private static AgentTool makeTool(String name, String description, String schema,
                                      ApprovalRequirement approvalRequirement) {
        return new AgentTool() {
            @Override
            public ToolSpec spec() {
                ToolCapabilityEnvelope envelope = "run_shell".equals(name)
                        ? ToolCapabilityEnvelope.shell() : ToolCapabilityEnvelope.repositoryRead();
                return ToolSpec.builder().name(name).description(description).inputSchema(schema)
                        .capabilityEnvelope(envelope)
                        .approvalRequirement(approvalRequirement).build();
            }

            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.success("ok", false, 0L);
            }
        };
    }

    private static AgentTool makeTool(String name) {
        return makeTool(name, "tool " + name, VALID_SCHEMA, ApprovalRequirement.NONE);
    }

    @Test
    public void registryKeepsExplicitInsertionOrder() {
        List<AgentTool> tools = List.of(
                makeTool("run_shell"), makeTool("read_file"), makeTool("list_files"));
        ToolRegistry registry = new ToolRegistry(tools, new ToolSchemaValidator(mapper));
        List<ToolSpec> specs = registry.specs();
        assertEquals("run_shell", specs.get(0).getName());
        assertEquals("read_file", specs.get(1).getName());
        assertEquals("list_files", specs.get(2).getName());
    }

    @Test
    public void containsChecksKnownTool() {
        ToolRegistry registry = new ToolRegistry(List.of(makeTool("search")), new ToolSchemaValidator(mapper));
        assertTrue(registry.contains("search"));
        assertFalse(registry.contains("unknown"));
    }

    @Test
    public void validateInputAcceptsValidArgs() {
        ToolRegistry registry = new ToolRegistry(List.of(makeTool("read_file")), new ToolSchemaValidator(mapper));
        var result = registry.validateInput("read_file", mapper.createObjectNode().put("p", "x"));
        assertTrue(result.valid());
    }

    @Test
    public void validateInputRejectsUnknownTool() {
        ToolRegistry registry = new ToolRegistry(List.of(), new ToolSchemaValidator(mapper));
        var result = registry.validateInput("nope", mapper.createObjectNode());
        assertFalse(result.valid());
        assertEquals("unknown_tool", result.errors().get(0).keyword());
    }

    @Test
    public void callUnknownToolReturnsFailure() {
        ToolRegistry registry = new ToolRegistry(List.of(), new ToolSchemaValidator(mapper));
        var result = registry.call(ToolCall.builder().name("unknown").input(mapper.createObjectNode()).build());
        assertEquals("unknown_tool", result.getErrorCode());
    }

    @Test
    public void callKnownToolReturnsSuccess() {
        ToolRegistry registry = new ToolRegistry(List.of(makeTool("read_file")), new ToolSchemaValidator(mapper));
        var result = registry.call(ToolCall.builder().name("read_file").input(mapper.createObjectNode()).build());
        assertTrue(result.isSuccess());
        assertEquals("ok", result.getObservation());
    }

    @Test
    public void approvalRequirementIsReportedSeparatelyFromEffects() {
        ToolRegistry registry = new ToolRegistry(
                List.of(makeTool("run_shell", "d", VALID_SCHEMA,
                        ApprovalRequirement.SESSION_POLICY)), new ToolSchemaValidator(mapper));
        assertEquals(ApprovalRequirement.SESSION_POLICY, registry.approvalRequirement("run_shell"));
        assertEquals(ApprovalRequirement.NONE, registry.approvalRequirement("nope"));
        assertEquals(ToolCapabilityEnvelope.shell().toEffectProfile(),
                registry.assessEffect("run_shell",
                        ToolCall.builder().name("run_shell").build(),
                        cn.lunalhx.ai.domain.tool.model.ExecutionProfile.forRun(
                                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, false))
                        .profile());
    }

    @Test
    public void duplicateToolNameThrows() {
        try {
            new ToolRegistry(List.of(makeTool("read_file"), makeTool("read_file")), new ToolSchemaValidator(mapper));
            fail("expected duplicate tool name to throw");
        } catch (IllegalStateException expected) {
            // expected
        }
    }
}
