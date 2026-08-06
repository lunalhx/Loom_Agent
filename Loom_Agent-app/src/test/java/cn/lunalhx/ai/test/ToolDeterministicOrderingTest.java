package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Verifies the loom-code 7-tool explicit ordering: six base tools then delegate.
 */
public class ToolDeterministicOrderingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ToolSpec spec(String name) {
        return ToolSpec.builder()
                .name(name)
                .description("desc " + name)
                .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                .risky(false)
                .build();
    }

    @Test
    public void registryKeepsExplicitOrderOfSixBaseToolsThenDelegate() {
        ToolRegistry registry = new ToolRegistry(List.of(
                new cn.lunalhx.ai.infrastructure.loom.ListFilesTool(null),
                new cn.lunalhx.ai.infrastructure.loom.ReadFileTool(null),
                new cn.lunalhx.ai.infrastructure.loom.SearchTool(null),
                new cn.lunalhx.ai.infrastructure.loom.RunShellTool(null),
                new cn.lunalhx.ai.infrastructure.loom.WriteFileTool(null),
                new cn.lunalhx.ai.infrastructure.loom.PatchFileTool(null),
                new cn.lunalhx.ai.infrastructure.loom.DelegateTool(
                        (task, maxSteps, parentRunId, rootRunId, sessionId, workspace, summary) ->
                                "delegate_result:\nDone")),
                new ToolSchemaValidator(objectMapper));

        List<ToolSpec> specs = registry.specs();
        assertEquals(7, specs.size());
        assertEquals("list_files", specs.get(0).getName());
        assertEquals("read_file", specs.get(1).getName());
        assertEquals("search", specs.get(2).getName());
        assertEquals("run_shell", specs.get(3).getName());
        assertEquals("write_file", specs.get(4).getName());
        assertEquals("patch_file", specs.get(5).getName());
        assertEquals("delegate", specs.get(6).getName());
    }

    @Test
    public void baseSpecsExcludeDelegate() {
        ToolRegistry registry = new ToolRegistry(List.of(
                new cn.lunalhx.ai.infrastructure.loom.ReadFileTool(null),
                new cn.lunalhx.ai.infrastructure.loom.DelegateTool(
                        (task, maxSteps, parentRunId, rootRunId, sessionId, workspace, summary) ->
                                "delegate_result:\nDone")),
                new ToolSchemaValidator(objectMapper));

        List<ToolSpec> base = registry.baseSpecs();
        assertEquals(1, base.size());
        assertEquals("read_file", base.get(0).getName());
    }

    @Test
    public void fixedOrderMatchesLoomCodeToolNames() {
        assertEquals(List.of("list_files", "read_file", "search", "run_shell", "write_file", "patch_file"),
                ToolRegistry.BASE_TOOL_NAMES);
        assertEquals("delegate", ToolRegistry.DELEGATE_TOOL_NAME);
    }
}
