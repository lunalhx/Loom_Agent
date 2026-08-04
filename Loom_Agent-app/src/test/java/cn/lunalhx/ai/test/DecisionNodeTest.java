package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.flow.node.DecisionNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DecisionNodeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static AgentTool tool(String name, String schema) {
        return new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder().name(name).description("test tool " + name).inputSchema(schema).build();
            }

            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.success("ok", false, 0L);
            }
        };
    }

    // ---- Schema validation ----

    @Test
    public void missingRequiredFieldReturnsInvalidToolInput() {
        String schema = "{\"type\":\"object\",\"properties\":{\"cmd\":{\"type\":\"string\"}},\"required\":[\"cmd\"],\"additionalProperties\":false}";
        AgentTool t = tool("test_tool", schema);
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());

        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setModelOutput("{\"type\":\"action\",\"tool\":\"test_tool\",\"input\":{}}");
        ctx.setToolSpecs(List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.OBSERVATION, result.getNextNode());
        assertNotNull(ctx.getToolResult());
        assertEquals("invalid_tool_input", ctx.getToolResult().getErrorCode());
        assertTrue(ctx.getToolResult().getObservation().contains("cmd"));
    }

    @Test
    public void wrongTypeReturnsInvalidToolInput() {
        String schema = "{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\"}},\"required\":[\"count\"],\"additionalProperties\":false}";
        AgentTool t = tool("count_tool", schema);
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());

        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setModelOutput("{\"type\":\"action\",\"tool\":\"count_tool\",\"input\":{\"count\":\"not_a_number\"}}");
        ctx.setToolSpecs(List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.OBSERVATION, result.getNextNode());
        assertEquals("invalid_tool_input", ctx.getToolResult().getErrorCode());
    }

    @Test
    public void unknownPropertyRejectedWhenAdditionalPropertiesFalse() {
        String schema = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"additionalProperties\":false}";
        AgentTool t = tool("strict_tool", schema);
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());

        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setModelOutput("{\"type\":\"action\",\"tool\":\"strict_tool\",\"input\":{\"name\":\"test\",\"extra\":123}}");
        ctx.setToolSpecs(List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.OBSERVATION, result.getNextNode());
        assertEquals("invalid_tool_input", ctx.getToolResult().getErrorCode());
    }

    @Test
    public void validInputProceedsToApprovalGate() {
        String schema = "{\"type\":\"object\",\"properties\":{\"msg\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"msg\"],\"additionalProperties\":false}";
        AgentTool t = tool("msg_tool", schema);
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());

        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setModelOutput("{\"type\":\"action\",\"tool\":\"msg_tool\",\"input\":{\"msg\":\"hello\"}}");
        ctx.setToolSpecs(List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.APPROVAL_GATE, result.getNextNode());
        assertNull(ctx.getToolResult());
    }

    @Test
    public void validInputWithDefaultFillsNoValue() {
        String schema = "{\"type\":\"object\",\"properties\":{\"msg\":{\"type\":\"string\",\"default\":\"hi\"}},\"additionalProperties\":false}";
        AgentTool t = tool("default_tool", schema);
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());

        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setModelOutput("{\"type\":\"action\",\"tool\":\"default_tool\",\"input\":{}}");
        ctx.setToolSpecs(List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.APPROVAL_GATE, result.getNextNode());
        // default value is NOT injected by validator
        assertTrue(ctx.getDecision().getInput().path("msg").isMissingNode());
    }

    @Test
    public void invalidInputDoesNotCountAsParseError() {
        String schema = "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"}},\"required\":[\"x\"],\"additionalProperties\":false}";
        AgentTool t = tool("int_tool", schema);
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());

        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setParseErrors(0);
        ctx.setModelOutput("{\"type\":\"action\",\"tool\":\"int_tool\",\"input\":{}}");
        ctx.setToolSpecs(List.of(t.spec()));

        node.apply(ctx);

        assertEquals(0, ctx.getParseErrors());
    }

    // ---- Unknown tool ----

    @Test
    public void unknownToolReturnsUnknownToolError() {
        AgentTool t = tool("known", "{\"type\":\"object\",\"additionalProperties\":false}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());

        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setModelOutput("{\"type\":\"action\",\"tool\":\"unknown\",\"input\":{}}");
        ctx.setToolSpecs(List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.OBSERVATION, result.getNextNode());
        assertEquals("unknown_tool", ctx.getToolResult().getErrorCode());
        assertTrue(ctx.getToolResult().getObservation().contains("unknown"));
    }

    @Test
    public void toolInRegistryButNotInCurrentSpecsIsUnknown() {
        AgentTool t1 = tool("exposed", "{\"type\":\"object\",\"additionalProperties\":false}");
        AgentTool t2 = tool("hidden", "{\"type\":\"object\",\"additionalProperties\":false}");
        ToolRegistry registry = new ToolRegistry(List.of(t1, t2), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());

        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setModelOutput("{\"type\":\"action\",\"tool\":\"hidden\",\"input\":{}}");
        // only expose t1, not t2
        ctx.setToolSpecs(List.of(t1.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.OBSERVATION, result.getNextNode());
        assertEquals("unknown_tool", ctx.getToolResult().getErrorCode());
    }

    // ---- Reason field ----

    @Test
    public void reasonFieldParsedCorrectly() {
        AgentTool t = tool("r_tool", "{\"type\":\"object\",\"additionalProperties\":false}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());

        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setModelOutput("{\"type\":\"action\",\"tool\":\"r_tool\",\"input\":{},\"reason\":\"short reason\"}");
        ctx.setToolSpecs(List.of(t.spec()));

        node.apply(ctx);

        assertEquals("short reason", ctx.getDecision().getReason());
    }

    @Test
    public void oldThoughtFieldFallsBackToReason() {
        AgentTool t = tool("t_tool", "{\"type\":\"object\",\"additionalProperties\":false}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());

        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setModelOutput("{\"type\":\"action\",\"tool\":\"t_tool\",\"input\":{},\"thought\":\"old thought\"}");
        ctx.setToolSpecs(List.of(t.spec()));

        node.apply(ctx);

        assertEquals("old thought", ctx.getDecision().getReason());
    }

    @Test
    public void reasonTakesPriorityOverThought() {
        AgentTool t = tool("p_tool", "{\"type\":\"object\",\"additionalProperties\":false}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());

        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setModelOutput(
                "{\"type\":\"action\",\"tool\":\"p_tool\",\"input\":{},\"reason\":\"new reason\",\"thought\":\"old thought\"}");
        ctx.setToolSpecs(List.of(t.spec()));

        node.apply(ctx);

        assertEquals("new reason", ctx.getDecision().getReason());
    }

    @Test
    public void reasonTruncatedTo240Chars() {
        AgentTool t = tool("trunc_tool", "{\"type\":\"object\",\"additionalProperties\":false}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());

        String longReason = "A".repeat(300);
        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setModelOutput(
                "{\"type\":\"action\",\"tool\":\"trunc_tool\",\"input\":{},\"reason\":\"" + longReason + "\"}");
        ctx.setToolSpecs(List.of(t.spec()));

        node.apply(ctx);

        assertEquals(240, ctx.getDecision().getReason().length());
    }

    // ---- Helper ----

    private AgentRuntimeProperties buildProps() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.setWorkspaceRoot(".");
        props.setParseErrorMaxAttempts(3);
        return props;
    }

    // ---- Protocol normalization: tool-name-as-type ----

    @Test
    public void toolNameAsTypeNormalizedToAction() {
        // Model emits {"type":"todo_write","input":{...}} instead of canonical form
        String todoSchema = "{\"type\":\"object\",\"properties\":{\"todos\":{\"type\":\"array\",\"minItems\":1,\"items\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"},\"status\":{\"type\":\"string\",\"enum\":[\"pending\",\"in_progress\",\"completed\",\"blocked\",\"skipped\"]}},\"required\":[\"status\"],\"additionalProperties\":false}}},\"required\":[\"todos\"],\"additionalProperties\":false}";
        AgentTool t = tool("todo_write", todoSchema);
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());

        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setModelOutput("{\"type\":\"todo_write\",\"input\":{\"todos\":[{\"id\":\"verify\",\"content\":\"tests passed\",\"status\":\"completed\"}]}}");
        ctx.setToolSpecs(List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        // Should be normalized and proceed to APPROVAL_GATE (not REPLAN_GUARD)
        assertEquals(AgentNodeNames.APPROVAL_GATE, result.getNextNode());
        assertNull(ctx.getToolResult());
        assertNotNull(ctx.getDecision());
        assertEquals("action", ctx.getDecision().getType());
        assertEquals("todo_write", ctx.getDecision().getTool());
    }

    @Test
    public void toolNameAsTypeWithoutToolFieldNormalized() {
        // Only normalize when tool field is missing (not when both type and tool exist)
        String schema = "{\"type\":\"object\",\"properties\":{\"msg\":{\"type\":\"string\"}},\"additionalProperties\":false}";
        AgentTool t = tool("msg_tool", schema);
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());

        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setModelOutput("{\"type\":\"msg_tool\",\"tool\":\"other_tool\",\"input\":{\"msg\":\"hello\"}}");
        ctx.setToolSpecs(List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        // Should NOT normalize (tool field already exists) — goes to parse error
        // The parser will see type="msg_tool" which is not "action"/"final"
        assertEquals(AgentNodeNames.RENDER_PROMPT, result.getNextNode());
    }

    @Test
    public void unknownTypeNotNormalized() {
        // Unknown type should NOT be guessed — parser rejects it
        String schema = "{\"type\":\"object\",\"properties\":{\"msg\":{\"type\":\"string\"}},\"additionalProperties\":false}";
        AgentTool t = tool("msg_tool", schema);
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());

        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setModelOutput("{\"type\":\"unknown_tool\",\"input\":{\"msg\":\"hello\"}}");
        ctx.setToolSpecs(List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        // Should NOT normalize unknown type — goes to parse error
        assertEquals(AgentNodeNames.RENDER_PROMPT, result.getNextNode());
    }

    @Test
    public void normalizedTodoWriteStillSchemaValidated() {
        // After normalization, invalid input should still fail schema validation
        String todoSchema = "{\"type\":\"object\",\"properties\":{\"todos\":{\"type\":\"array\",\"minItems\":1,\"items\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"},\"status\":{\"type\":\"string\",\"enum\":[\"pending\",\"in_progress\",\"completed\",\"blocked\",\"skipped\"]}},\"required\":[\"status\"],\"additionalProperties\":false}}},\"required\":[\"todos\"],\"additionalProperties\":false}";
        AgentTool t = tool("todo_write", todoSchema);
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());

        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        // Missing required "todos" field in input
        ctx.setModelOutput("{\"type\":\"todo_write\",\"input\":{}}");
        ctx.setToolSpecs(List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        // Normalization succeeded but schema validation should fail
        assertEquals(AgentNodeNames.OBSERVATION, result.getNextNode());
        assertEquals("invalid_tool_input", ctx.getToolResult().getErrorCode());
    }

    @Test
    public void standardActionTypeNotNormalized() {
        // Standard {"type":"action",...} should not be affected
        String schema = "{\"type\":\"object\",\"properties\":{\"msg\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"msg\"],\"additionalProperties\":false}";
        AgentTool t = tool("msg_tool", schema);
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());

        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setModelOutput("{\"type\":\"action\",\"tool\":\"msg_tool\",\"input\":{\"msg\":\"hello\"}}");
        ctx.setToolSpecs(List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.APPROVAL_GATE, result.getNextNode());
    }

    @Test
    public void finalTypeNotNormalized() {
        // {"type":"final",...} should not be affected
        AgentTool t = tool("todo_write", "{\"type\":\"object\",\"additionalProperties\":false}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());

        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setModelOutput("{\"type\":\"final\",\"answer\":\"done\",\"evidence\":[]}");
        ctx.setToolSpecs(List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.FINAL_ANSWER, result.getNextNode());
    }

    @Test
    public void nonObjectInputShouldReturnStructuredParseError() {
        AgentTool t = tool("msg_tool",
                "{\"type\":\"object\",\"additionalProperties\":false}");
        ToolRegistry registry =
                new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, registry, buildProps());
        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setModelOutput(
                "{\"type\":\"action\",\"tool\":\"msg_tool\",\"input\":[]}");
        ctx.setToolSpecs(List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.RENDER_PROMPT, result.getNextNode());
        assertTrue(ctx.getToolResult().getMessage().contains("NON_OBJECT_INPUT"));
    }
}
