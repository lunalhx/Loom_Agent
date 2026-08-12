package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.skill.model.SkillResourceReadException;
import cn.lunalhx.ai.domain.skill.service.SkillResourceReader;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.CallEffectAssessment;
import cn.lunalhx.ai.domain.tool.model.EffectProfile;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolEffect;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded read access to indexed resources of active Skills.
 * Results are untrusted Tool data and may include repository or external read effects.
 */
@Component
public class ReadSkillResourceTool implements AgentTool {

    private final SkillResourceReader reader = new SkillResourceReader();

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("read_skill_resource")
                .description("Read a supporting file from an active Skill's indexed resources.")
                .inputSchema("{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{" +
                        "\"skill\":{\"type\":\"string\",\"minLength\":1,\"description\":\"active skill name\"}," +
                        "\"path\":{\"type\":\"string\",\"minLength\":1,\"description\":\"indexed relative path\"}," +
                        "\"offset\":{\"type\":\"integer\",\"minimum\":0,\"default\":0,\"description\":\"byte offset\"}," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":"
                        + SkillResourceReader.DEFAULT_CHUNK_BYTES
                        + ",\"default\":" + SkillResourceReader.DEFAULT_CHUNK_BYTES
                        + ",\"description\":\"max bytes to return\"}" +
                        "}," +
                        "\"required\":[\"skill\",\"path\"]," +
                        "\"additionalProperties\":false" +
                        "}")
                .capabilityEnvelope(ToolCapabilityEnvelope.trusted(
                        EnumSet.of(ToolEffect.REPOSITORY_READ, ToolEffect.EXTERNAL_READ),
                        cn.lunalhx.ai.domain.tool.model.OutboundDisclosure.NONE))
                .build();
    }

    @Override
    public CallEffectAssessment assessEffect(ToolCall call, ExecutionProfile executionProfile) {
        List<ActiveSkillSnapshot> active = call.getActiveSkills();
        String skillName = text(call, "skill");
        String rawPath = text(call, "path");
        if (active == null || skillName == null || rawPath == null) {
            return CallEffectAssessment.untrusted();
        }
        ActiveSkillSnapshot skill = active.stream()
                .filter(item -> skillName.equals(item.name()))
                .findFirst()
                .orElse(null);
        if (skill == null || skill.packageRoot() == null || executionProfile == null) {
            return CallEffectAssessment.untrusted();
        }
        Path workspace = executionProfile.workspace();
        if (workspace == null) {
            return CallEffectAssessment.trusted(new EffectProfile(
                    Set.of(ToolEffect.EXTERNAL_READ),
                    cn.lunalhx.ai.domain.tool.model.OutboundDisclosure.NONE, true));
        }
        try {
            Path packageRoot = skill.packageRoot().toRealPath();
            Path workspaceRoot = workspace.toRealPath();
            if (packageRoot.startsWith(workspaceRoot)) {
                return CallEffectAssessment.trusted(new EffectProfile(
                        Set.of(ToolEffect.REPOSITORY_READ),
                        cn.lunalhx.ai.domain.tool.model.OutboundDisclosure.NONE, true));
            }
        } catch (Exception ignored) {
            return CallEffectAssessment.untrusted();
        }
        return CallEffectAssessment.trusted(new EffectProfile(
                Set.of(ToolEffect.EXTERNAL_READ),
                cn.lunalhx.ai.domain.tool.model.OutboundDisclosure.NONE, true));
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            List<ActiveSkillSnapshot> active = call.getActiveSkills();
            if (active == null || active.isEmpty()) {
                return failure("no_active_skill", "no active skill for this Run", startedAt);
            }
            String skill = text(call, "skill");
            String path = text(call, "path");
            if (skill == null || skill.isBlank() || path == null || path.isBlank()) {
                return failure("invalid_arguments", "skill and path are required", startedAt);
            }
            int offset = intValue(call, "offset", 0);
            int limit = intValue(call, "limit", SkillResourceReader.DEFAULT_CHUNK_BYTES);
            SkillResourceReader.ReadResult result =
                    reader.read(active, skill, path, offset, limit);
            return ToolResult.success(LoomToolSupport.clip(result.content()), result.truncated(), elapsed(startedAt));
        } catch (SkillResourceReadException e) {
            return failure("read_skill_resource_failed", e.getMessage(), startedAt);
        } catch (Exception e) {
            return failure("read_skill_resource_failed", e.getMessage(), startedAt);
        }
    }

    private String text(ToolCall call, String key) {
        if (call.getInput() == null || !call.getInput().has(key) || call.getInput().path(key).isNull()) {
            return null;
        }
        return call.getInput().path(key).asText();
    }

    private int intValue(ToolCall call, String key, int def) {
        if (call.getInput() == null || !call.getInput().has(key) || call.getInput().path(key).isNull()) {
            return def;
        }
        return call.getInput().path(key).asInt(def);
    }

    private ToolResult failure(String code, String message, long startedAt) {
        return ToolResult.failure(code, message, elapsed(startedAt));
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
