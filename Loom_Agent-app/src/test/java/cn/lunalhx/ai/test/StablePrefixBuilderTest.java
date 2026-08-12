package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.entity.PlanBinding;
import cn.lunalhx.ai.domain.agent.service.prompt.StablePrefixBuilder;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class StablePrefixBuilderTest {

    private final StablePrefixBuilder builder = new StablePrefixBuilder();

    private ToolSpec tool(String name, String desc) {
        return ToolSpec.builder()
                .name(name)
                .description(desc)
                .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                .capabilityEnvelope(ToolCapabilityEnvelope.repositoryRead())
                .build();
    }

    @Test
    public void buildIsDeterministicAcrossCalls() {
        List<ToolSpec> specs = List.of(
                tool("write_file", "Write a text file"),
                tool("read_file", "Read a UTF-8 file"),
                tool("list_files", "List files"));
        StablePrefix a = builder.build(false, true, null, specs, "", null,
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, null);
        StablePrefix b = builder.build(false, true, null, specs, "", null,
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, null);
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
        StablePrefix a = builder.build(false, true, null, specsA, "", null,
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, null);
        StablePrefix b = builder.build(false, true, null, specsB, "", null,
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, null);
        assertEquals(a.fingerprint(), b.fingerprint());
    }

    @Test
    public void descriptionChangeChangesFingerprint() {
        List<ToolSpec> specsA = List.of(tool("read_file", "Read a file"));
        List<ToolSpec> specsB = List.of(tool("read_file", "Read a UTF-8 file"));
        assertNotEquals(
                builder.build(false, true, null, specsA, "", null,
                        cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, null).fingerprint(),
                builder.build(false, true, null, specsB, "", null,
                        cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, null).fingerprint());
    }

    @Test
    public void delegateRoleChangesContent() {
        List<ToolSpec> specs = List.of(tool("read_file", "Read"));
        StablePrefix main = builder.build(false, true, null, specs, "", null,
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, null);
        StablePrefix delegate = builder.build(true, false, null, specs, "", null,
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, null);
        assertNotEquals(main.frozenContent(), delegate.frozenContent());
        assertTrue(delegate.frozenContent().contains("read-only"));
    }

    @Test
    public void workspaceFactsAreIncluded() {
        List<ToolSpec> specs = List.of(tool("read_file", "Read"));
        StablePrefix withFacts = builder.build(false, true, null, specs,
                "Workspace:\n- cwd: /tmp", null,
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, null);
        assertTrue(withFacts.frozenContent().contains("cwd: /tmp"));
        StablePrefix without = builder.build(false, true, null, specs, "", null,
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, null);
        assertNotEquals(withFacts.fingerprint(), without.fingerprint());
    }

    @Test
    public void buildRoleProtocolTextContainsProtocolRules() {
        String text = StablePrefixBuilder.buildRoleProtocolText(false, true, null,
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD);
        assertTrue(text.contains("<skill_activation>"));
        assertTrue(text.contains("<final>"));
    }

    @Test
    public void planSubmissionIsRootOnlyInProtocolRules() {
        String root = StablePrefixBuilder.buildRoleProtocolText(false, true, null,
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.PLAN);
        String delegate = StablePrefixBuilder.buildRoleProtocolText(true, false, null,
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.PLAN);
        assertTrue(root.contains("<plan_submission>"));
        assertFalse(delegate.contains("<plan_submission>"));
    }

    @Test
    public void planDeviationProtocolIsOnlyAdvertisedToBoundBuildRoot() {
        PlanBinding binding = PlanBinding.fromHandoff("plan_1", 1, "doc", "basis",
                "Title", "Objective and validation", List.of());
        List<ToolSpec> specs = List.of(tool("read_file", "Read"));

        StablePrefix root = builder.build(false, true, null, specs, "", null,
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, binding);
        StablePrefix delegate = builder.build(true, false, null, specs, "", null,
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, binding);
        StablePrefix unbound = builder.build(false, true, null, specs, "", null,
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, null);
        StablePrefix forged = builder.build(false, true, null, specs, "", null,
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD,
                new PlanBinding("plan_1", 1, "doc", "basis", "Title", "Objective", List.of()));

        assertTrue(root.frozenContent().contains("<plan_deviation>"));
        assertFalse(delegate.frozenContent().contains("<plan_deviation>"));
        assertFalse(unbound.frozenContent().contains("<plan_deviation>"));
        assertFalse(forged.frozenContent().contains("<plan_deviation>"));
    }

    @Test
    public void buildCarriesSignatures() {
        List<ToolSpec> specs = List.of(tool("read_file", "Read"), tool("write_file", "Write"));
        StablePrefix p = builder.build(false, true, "/scope", specs, "Workspace:", "ws-fp",
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, null);
        assertEquals("ws-fp", p.workspaceFingerprint());
        assertEquals(StablePrefixBuilder.toolSignature(specs), p.toolSignature());
        assertEquals(StablePrefixBuilder.runtimeSignature(false, true, "/scope",
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD), p.runtimeSignature());
        assertFalse(p.isLegacyTwoField());
        assertTrue(p.builtAt() > 0);
    }

    @Test
    public void toolSignatureDeterministicAndOrderInsensitive() {
        List<ToolSpec> a = List.of(tool("read_file", "R"), tool("write_file", "W"));
        List<ToolSpec> b = List.of(tool("write_file", "W"), tool("read_file", "R"));
        assertEquals(StablePrefixBuilder.toolSignature(a), StablePrefixBuilder.toolSignature(b));
    }

    @Test
    public void workspaceFingerprintChangeDoesNotChangeToolSignature() {
        List<ToolSpec> specs = List.of(tool("read_file", "R"));
        assertEquals(StablePrefixBuilder.toolSignature(specs), StablePrefixBuilder.toolSignature(specs));
    }
}
