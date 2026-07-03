package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.flow.node.RenderPromptNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.context.ContextWindowManager;
import cn.lunalhx.ai.domain.agent.service.prompt.LedgerPromptServices;
import cn.lunalhx.ai.domain.agent.service.prompt.RenderPromptResources;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryContextArtifactRepository;
import cn.lunalhx.ai.infrastructure.context.InMemoryContextBlobStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ToolRegistryTest {

    private static final String VALID_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"p\":{\"type\":\"string\"}},\"additionalProperties\":false}";

    private final ObjectMapper mapper = new ObjectMapper();

    // ---- helpers ----

    private static AgentTool makeTool(String name, String description, String schema) {
        return new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder().name(name).description(description).inputSchema(schema).build();
            }

            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.success("ok", false, 0L);
            }
        };
    }

    private static AgentTool makeTool(String name) {
        return makeTool(name, "tool " + name, VALID_SCHEMA);
    }

    // ==================== 1. deterministic ordering ====================

    @Test
    public void shuffledCollectionProducesIdenticalSpecs() {
        List<AgentTool> order1 = List.of(
                makeTool("zebra"),
                makeTool("alpha"),
                makeTool("gamma"),
                makeTool("beta")
        );
        // Reverse insertion order
        List<AgentTool> order2 = List.of(
                makeTool("beta"),
                makeTool("gamma"),
                makeTool("alpha"),
                makeTool("zebra")
        );
        // Arbitrary shuffle
        List<AgentTool> order3 = List.of(
                makeTool("gamma"),
                makeTool("zebra"),
                makeTool("beta"),
                makeTool("alpha")
        );

        ToolRegistry r1 = new ToolRegistry(order1, new ToolSchemaValidator(mapper));
        ToolRegistry r2 = new ToolRegistry(order2, new ToolSchemaValidator(mapper));
        ToolRegistry r3 = new ToolRegistry(order3, new ToolSchemaValidator(mapper));

        List<ToolSpec> specs1 = r1.specs();
        List<ToolSpec> specs2 = r2.specs();
        List<ToolSpec> specs3 = r3.specs();

        // All should be in natural order: alpha, beta, gamma, zebra
        List<String> expected = List.of("alpha", "beta", "gamma", "zebra");
        assertEquals(expected, specs1.stream().map(ToolSpec::getName).toList());
        assertEquals(expected, specs2.stream().map(ToolSpec::getName).toList());
        assertEquals(expected, specs3.stream().map(ToolSpec::getName).toList());
    }

    @Test
    public void naturalOrderIsLexicographic() {
        // Verify that natural (String.compareTo) order is used, not anything else
        List<AgentTool> tools = List.of(
                makeTool("B_tool"),   // uppercase B (ASCII 66)
                makeTool("a_tool"),   // lowercase a (ASCII 97)
                makeTool("1_tool"),   // digit (ASCII 49)
                makeTool("_tool")     // underscore (ASCII 95)
        );

        ToolRegistry registry = new ToolRegistry(tools, new ToolSchemaValidator(mapper));
        List<String> names = registry.specs().stream().map(ToolSpec::getName).toList();

        // String.compareTo: '1'(49) < 'B'(66) < '_'(95) < 'a'(97)
        assertEquals(List.of("1_tool", "B_tool", "_tool", "a_tool"), names);
    }

    @Test
    public void singleToolSpecsIsConsistent() {
        ToolRegistry r = new ToolRegistry(List.of(makeTool("only")), new ToolSchemaValidator(mapper));
        assertEquals(List.of("only"), r.specs().stream().map(ToolSpec::getName).toList());
    }

    @Test
    public void emptyToolCollectionProducesEmptySpecs() {
        ToolRegistry r = new ToolRegistry(List.of(), new ToolSchemaValidator(mapper));
        assertTrue(r.specs().isEmpty());
    }

    // ==================== 2. validation: duplicate names still fail ====================

    @Test
    public void duplicateNameThrows() {
        List<AgentTool> tools = List.of(
                makeTool("dup", "first", VALID_SCHEMA),
                makeTool("dup", "second", VALID_SCHEMA)
        );
        try {
            new ToolRegistry(tools, new ToolSchemaValidator(mapper));
            fail("should have thrown for duplicate tool name");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("dup"));
        }
    }

    // ==================== 3. validation: illegal specs still fail ====================

    @Test
    public void nullNameThrows() {
        AgentTool tool = makeTool(null, "desc", VALID_SCHEMA);
        try {
            new ToolRegistry(List.of(tool), new ToolSchemaValidator(mapper));
            fail("should have thrown for null name");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("工具名不能为空"));
        }
    }

    @Test
    public void blankNameThrows() {
        AgentTool tool = makeTool("   ", "desc", VALID_SCHEMA);
        try {
            new ToolRegistry(List.of(tool), new ToolSchemaValidator(mapper));
            fail("should have thrown for blank name");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("工具名不能为空"));
        }
    }

    @Test
    public void emptyNameThrows() {
        AgentTool tool = makeTool("", "desc", VALID_SCHEMA);
        try {
            new ToolRegistry(List.of(tool), new ToolSchemaValidator(mapper));
            fail("should have thrown for empty name");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("工具名不能为空"));
        }
    }

    @Test
    public void nullDescriptionThrows() {
        AgentTool tool = makeTool("t", null, VALID_SCHEMA);
        try {
            new ToolRegistry(List.of(tool), new ToolSchemaValidator(mapper));
            fail("should have thrown for null description");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("description"));
        }
    }

    @Test
    public void blankDescriptionThrows() {
        AgentTool tool = makeTool("t", "  ", VALID_SCHEMA);
        try {
            new ToolRegistry(List.of(tool), new ToolSchemaValidator(mapper));
            fail("should have thrown for blank description");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("description"));
        }
    }

    @Test
    public void nullSchemaThrows() {
        AgentTool tool = makeTool("t", "desc", null);
        try {
            new ToolRegistry(List.of(tool), new ToolSchemaValidator(mapper));
            fail("should have thrown for null schema");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Schema"));
        }
    }

    @Test
    public void blankSchemaThrows() {
        AgentTool tool = makeTool("t", "desc", "");
        try {
            new ToolRegistry(List.of(tool), new ToolSchemaValidator(mapper));
            fail("should have thrown for blank schema");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Schema"));
        }
    }

    @Test
    public void invalidSchemaThrows() {
        AgentTool tool = makeTool("t", "desc", "not valid json schema");
        try {
            new ToolRegistry(List.of(tool), new ToolSchemaValidator(mapper));
            fail("should have thrown for invalid schema");
        } catch (Exception e) {
            // ToolSchemaValidator.compile throws on invalid schema
        }
    }

    // ==================== 4. RenderPromptNode tool directory equivalence ====================

    @Test
    public void renderPromptToolDirectoryIsDeterministic() {
        // Same tools, different injection orders → identical tool directory text

        List<AgentTool> orderA = List.of(
                makeTool("write", "Write a file",
                        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"),
                makeTool("read", "Read a file",
                        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"),
                makeTool("delete", "Delete a file",
                        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}")
        );

        List<AgentTool> orderB = List.of(
                makeTool("delete", "Delete a file",
                        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"),
                makeTool("read", "Read a file",
                        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"),
                makeTool("write", "Write a file",
                        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}")
        );

        String promptA = renderWithToolSpecs(orderA);
        String promptB = renderWithToolSpecs(orderB);

        // Extract the "可用工具：" section from both prompts
        String toolsA = extractToolSection(promptA);
        String toolsB = extractToolSection(promptB);

        assertNotNull("prompt A should contain tool section", toolsA);
        assertNotNull("prompt B should contain tool section", toolsB);
        assertEquals("tool directory must be character-identical regardless of insertion order",
                toolsA, toolsB);
    }

    @Test
    public void renderPromptToolDirectoryGolden() {
        // Golden test: known tool set produces known sorted directory text

        List<AgentTool> tools = List.of(
                makeTool("search", "Search the codebase",
                        "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}"),
                makeTool("read", "Read a file",
                        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"),
                makeTool("list", "List directory",
                        "{\"type\":\"object\",\"properties\":{\"dir\":{\"type\":\"string\"}}}")
        );

        String prompt = renderWithToolSpecs(tools);
        String toolSection = extractToolSection(prompt);

        // Expected: sorted by name → list, read, search
        String expected =
                "- list: List directory input={\"type\":\"object\",\"properties\":{\"dir\":{\"type\":\"string\"}}}\n" +
                "- read: Read a file input={\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}\n" +
                "- search: Search the codebase input={\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}";

        assertEquals(expected, toolSection);
    }

    // ==================== 5. all methods use the same frozen map ====================

    @Test
    public void containsUsesSameMapAsSpecs() {
        List<AgentTool> tools = List.of(
                makeTool("zulu"),
                makeTool("alpha")
        );
        ToolRegistry r = new ToolRegistry(tools, new ToolSchemaValidator(mapper));
        assertTrue(r.contains("alpha"));
        assertTrue(r.contains("zulu"));
        // specs returns sorted names
        assertEquals(List.of("alpha", "zulu"), r.specs().stream().map(ToolSpec::getName).toList());
    }

    @Test
    public void unknownToolInLookupMethodsReturnsCorrectErrors() {
        ToolRegistry r = new ToolRegistry(List.of(makeTool("known")), new ToolSchemaValidator(mapper));

        // validate
        var result = r.validateInput("unknown", mapper.createObjectNode());
        assertEquals("unknown_tool", result.errors().get(0).keyword());

        // call
        var callResult = r.call(ToolCall.builder().name("unknown").input(mapper.createObjectNode()).build());
        assertEquals("unknown_tool", callResult.getErrorCode());

        // policy
        var policy = r.policy(ToolCall.builder().name("unknown").input(mapper.createObjectNode()).build());
        assertEquals(cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
    }

    // ==================== helpers ====================

    private String renderWithToolSpecs(List<AgentTool> tools) {
        ToolRegistry registry = new ToolRegistry(tools, new ToolSchemaValidator(mapper));

        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getContext().setEnabled(false);
        ContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        ContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager cwm = new ContextWindowManager(properties, artifactRepository, blobStore);
        RenderPromptNode node = new RenderPromptNode(cwm,
                RenderPromptResources.withStorage(artifactRepository, blobStore),
                LedgerPromptServices.disabled());

        AgentContext context = new AgentContext();
        context.setRunId("ordering-test");
        context.setRootRunId("ordering-test");
        context.setQuestion("test");
        context.setMaxSteps(5);
        context.setStep(0);
        context.setStartedAt(Instant.now());
        context.setToolSpecs(registry.specs());

        node.apply(context);
        return context.getCurrentPrompt();
    }

    private static String extractToolSection(String prompt) {
        String marker = "可用工具：\n";
        int start = prompt.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = prompt.indexOf("\n\n", start);
        if (end < 0) end = prompt.indexOf("\n用户问题：", start);
        if (end < 0) end = prompt.length();
        return prompt.substring(start, end).stripTrailing();
    }
}
