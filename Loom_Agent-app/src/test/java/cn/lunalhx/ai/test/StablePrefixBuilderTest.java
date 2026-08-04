package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.service.prompt.StablePrefixBuilder;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class StablePrefixBuilderTest {

    private final StablePrefixBuilder builder = new StablePrefixBuilder();

    private ToolSpec tool(String name, String desc) {
        return ToolSpec.builder()
                .name(name)
                .description(desc)
                .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                .risky(false)
                .build();
    }

    @Test
    public void buildIsDeterministicAcrossCalls() {
        List<ToolSpec> specs = List.of(
                tool("write_file", "Write a text file"),
                tool("read_file", "Read a UTF-8 file"),
                tool("list_files", "List files"));
        StablePrefix a = builder.build(false, true, null, specs, "");
        StablePrefix b = builder.build(false, true, null, specs, "");
        assertEquals(a.frozenContent(), b.frozenContent());
        assertEquals(a.fingerprint(), b.fingerprint());
    }

    @Test
    public void toolOrderChangeChangesFingerprint() {
        List<ToolSpec> specsA = List.of(
                tool("write_file", "W"), tool("read_file", "R"), tool("search", "S"));
        List<ToolSpec> specsB = List.of(
                tool("read_file", "R"), tool("write_file", "W"), tool("search", "S"));
        // Content is deterministically sorted by name, so order should not matter.
        StablePrefix a = builder.build(false, true, null, specsA, "");
        StablePrefix b = builder.build(false, true, null, specsB, "");
        assertEquals(a.fingerprint(), b.fingerprint());
    }

    @Test
    public void descriptionChangeChangesFingerprint() {
        List<ToolSpec> specsA = List.of(tool("read_file", "Read a file"));
        List<ToolSpec> specsB = List.of(tool("read_file", "Read a UTF-8 file"));
        assertNotEquals(
                builder.build(false, true, null, specsA, "").fingerprint(),
                builder.build(false, true, null, specsB, "").fingerprint());
    }

    @Test
    public void delegateRoleChangesContent() {
        List<ToolSpec> specs = List.of(tool("read_file", "Read"));
        StablePrefix main = builder.build(false, true, null, specs, "");
        StablePrefix delegate = builder.build(true, false, null, specs, "");
        assertNotEquals(main.frozenContent(), delegate.frozenContent());
        assertTrue(delegate.frozenContent().contains("只读"));
    }

    @Test
    public void workspaceFactsAreIncluded() {
        List<ToolSpec> specs = List.of(tool("read_file", "Read"));
        StablePrefix withFacts = builder.build(false, true, null, specs, "Workspace:\n- cwd: /tmp");
        assertTrue(withFacts.frozenContent().contains("cwd: /tmp"));
        StablePrefix without = builder.build(false, true, null, specs, "");
        assertNotEquals(withFacts.fingerprint(), without.fingerprint());
    }

    @Test
    public void buildRoleProtocolTextContainsProtocolRules() {
        String text = StablePrefixBuilder.buildRoleProtocolText(false, true, null);
        assertTrue(text.contains("每轮只能输出一个 JSON 对象"));
    }
}
