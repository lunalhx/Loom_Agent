package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SkillRunBootstrapResourceTest {

    @Test
    public void explicitActivationIndexesResourcesForCatalogProjection() throws Exception {
        Path home = Files.createTempDirectory("skill-bootstrap-home");
        Path workspace = Files.createTempDirectory("skill-bootstrap-workspace").toRealPath();
        Path skillDir = workspace.resolve(".agents/skills/doc-skill");
        Files.createDirectories(skillDir.resolve("references"));
        Files.writeString(skillDir.resolve("references/guide.md"), "Guide body.", StandardCharsets.UTF_8);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: doc-skill
                description: Docs.
                ---
                Use references.
                """, StandardCharsets.UTF_8);

        AgentContext context = new AgentContext();
        context.setQuestion("$doc-skill read guide");
        context.setResolvedWorkspace(workspace);
        context.setCollaborationMode(CollaborationMode.BUILD);

        ToolRegistry registry = new ToolRegistry(List.of(readSkillResourceStub()),
                new ToolSchemaValidator(new com.fasterxml.jackson.databind.ObjectMapper()));
        new SkillRunBootstrap(new cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties(), registry)
                .prepareRun(context, home);

        ActiveSkillSnapshot active = context.getActiveSkills().get(0);
        assertFalse(active.resources().isEmpty());
        List<ToolSpec> specs = SkillToolCatalogProjector.project(context, registry);
        assertTrue(specs.stream().anyMatch(spec ->
                SkillToolCatalogProjector.READ_SKILL_RESOURCE.equals(spec.getName())));
    }

    private static AgentTool readSkillResourceStub() {
        return new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder()
                        .name(SkillToolCatalogProjector.READ_SKILL_RESOURCE)
                        .description("Read indexed skill resources.")
                        .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                        .capabilityEnvelope(ToolCapabilityEnvelope.repositoryRead())
                        .build();
            }

            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.success("ok", false, 0L);
            }
        };
    }
}
