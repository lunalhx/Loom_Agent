package cn.lunalhx.ai.domain.skill.service;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SkillFrontmatterParserTest {

    private final SkillFrontmatterParser parser = new SkillFrontmatterParser();

    @Test
    public void parsesRequiredMetadataAndBodyBoundary() {
        var parsed = parser.parse("demo-skill", """
                ---
                name: demo-skill
                description: Demo workflow instructions.
                license: MIT
                compatibility: '>=21'
                metadata: {team: loom}
                ---
                # Demo body
                Run tests first.
                """.getBytes(StandardCharsets.UTF_8));

        assertTrue(parsed.valid());
        assertEquals("demo-skill", parsed.name());
        assertEquals("Demo workflow instructions.", parsed.description());
        assertEquals("MIT", parsed.license());
        assertEquals(">=21", parsed.compatibility());
        assertEquals("{\"team\":\"loom\"}", parsed.metadata());
        assertTrue(parsed.userInvocable());
        assertTrue(parsed.modelInvocable());
        assertEquals(List.of(), parsed.compatibilityDiagnostics());
    }

    @Test
    public void rejectsMissingDescription() {
        var parsed = parser.parse("demo-skill", """
                ---
                name: demo-skill
                ---
                body
                """.getBytes(StandardCharsets.UTF_8));

        assertFalse(parsed.valid());
        assertTrue(parsed.validationErrors().stream().anyMatch(error -> error.contains("description")));
    }

    @Test
    public void rejectsNameDirectoryMismatch() {
        var parsed = parser.parse("demo-skill", """
                ---
                name: other-skill
                description: Demo workflow instructions.
                ---
                body
                """.getBytes(StandardCharsets.UTF_8));

        assertFalse(parsed.valid());
        assertTrue(parsed.validationErrors().stream().anyMatch(error -> error.contains("name")));
    }

    @Test
    public void recordsInvocationRestrictionsWithoutChangingDefaults() {
        var parsed = parser.parse("manual-only", """
                ---
                name: manual-only
                description: Manual workflow.
                disable-model-invocation: true
                user-invocable: false
                ---
                body
                """.getBytes(StandardCharsets.UTF_8));

        assertTrue(parsed.valid());
        assertFalse(parsed.userInvocable());
        assertFalse(parsed.modelInvocable());
    }

    @Test
    public void unsupportedClaudeFieldsProduceCompatibilityDiagnosticsOnly() {
        var parsed = parser.parse("compat-skill", """
                ---
                name: compat-skill
                description: Compatibility demo.
                allowed-tools: [run_shell]
                agent: reviewer
                ---
                body
                """.getBytes(StandardCharsets.UTF_8));

        assertTrue(parsed.valid());
        assertTrue(parsed.userInvocable());
        assertTrue(parsed.modelInvocable());
        assertEquals(2, parsed.compatibilityDiagnostics().size());
        assertTrue(parsed.compatibilityDiagnostics().stream().anyMatch(d -> d.contains("allowed-tools")));
        assertTrue(parsed.compatibilityDiagnostics().stream().anyMatch(d -> d.contains("agent")));
    }
}
