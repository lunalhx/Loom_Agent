package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.SkillActivation;
import cn.lunalhx.ai.domain.agent.model.valobj.ActivatedSkillToolPolicy;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SkillToolPolicyEnforcementTest {

    @Test
    public void missingAllowedToolsIsUnrestricted() {
        ActivatedSkillToolPolicy policy = ActivatedSkillToolPolicy.from(List.of(activation(List.of(), false)));
        assertFalse(policy.restricted());
        assertTrue(policy.allows("write_file"));
    }

    @Test
    public void explicitEmptyAllowedToolsDeniesEveryTool() {
        ActivatedSkillToolPolicy policy = ActivatedSkillToolPolicy.from(List.of(activation(List.of(), true)));
        assertTrue(policy.restricted());
        assertFalse(policy.allows("read_file"));
    }

    @Test
    public void explicitPoliciesUseUnionAndFilterPromptCatalog() {
        ActivatedSkillToolPolicy policy = ActivatedSkillToolPolicy.from(List.of(
                activation(List.of("read_file"), true),
                activation(List.of("code_search"), true)));
        List<ToolSpec> filtered = policy.filter(List.of(spec("write_file"), spec("code_search"), spec("read_file")));
        assertTrue(policy.restricted());
        assertTrue(policy.allows("read_file"));
        assertTrue(filtered.stream().map(ToolSpec::getName).toList().equals(List.of("code_search", "read_file")));
    }

    @Test
    public void dispatchRejectsABypassedSkillRestriction() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("write_file")),
                new ToolSchemaValidator(new ObjectMapper()));
        ToolResult result = registry.call(ToolCall.builder()
                .name("write_file")
                .skillToolRestrictionActive(true)
                .allowedToolNames(List.of("read_file"))
                .build());
        assertFalse(result.isSuccess());
        assertTrue(result.getObservation().contains("skill_tool_not_allowed"));
    }

    private SkillActivation activation(List<String> tools, boolean declared) {
        return new SkillActivation("skill", null, "hash", null, Instant.EPOCH, 0, tools, declared);
    }

    private ToolSpec spec(String name) {
        return new ToolSpec(name, name, "{}");
    }

    private AgentTool tool(String name) {
        return new AgentTool() {
            @Override
            public ToolSpec spec() {
                return SkillToolPolicyEnforcementTest.this.spec(name);
            }

            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.success("called", false, 0);
            }
        };
    }
}
