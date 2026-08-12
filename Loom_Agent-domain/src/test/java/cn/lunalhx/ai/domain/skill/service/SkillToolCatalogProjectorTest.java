package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.skill.model.SkillResourceEntry;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SkillToolCatalogProjectorTest {

    private final ToolRegistry registry = new ToolRegistry(
            List.of(readSkillResourceStub()),
            new ToolSchemaValidator(new com.fasterxml.jackson.databind.ObjectMapper()));

    @Test
    public void hidesReadSkillResourceWhenNoIndexedResources() {
        AgentContext context = new AgentContext();
        context.setCollaborationMode(CollaborationMode.BUILD);
        context.setActiveSkills(List.of(new ActiveSkillSnapshot(
                "plain", "source", "body", "digest", Path.of("."), List.of())));
        List<ToolSpec> specs = SkillToolCatalogProjector.project(context, registry);
        assertFalse(specs.stream().anyMatch(spec ->
                SkillToolCatalogProjector.READ_SKILL_RESOURCE.equals(spec.getName())));
    }

    @Test
    public void exposesReadSkillResourceWhenIndexedResourcesExist() {
        AgentContext context = new AgentContext();
        context.setCollaborationMode(CollaborationMode.BUILD);
        context.setActiveSkills(List.of(new ActiveSkillSnapshot(
                "with-ref", "source", "body", "digest", Path.of("."),
                List.of(new SkillResourceEntry("references/guide.md", "abc")))));
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
