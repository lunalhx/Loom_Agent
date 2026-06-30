package cn.lunalhx.ai.infrastructure.skill;

import cn.lunalhx.ai.domain.agent.adapter.port.SkillRepository;
import cn.lunalhx.ai.domain.agent.model.entity.SkillDescriptor;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

public final class SkillTools {

    private SkillTools() {}

    // --- shared constants ---
    private static final Set<String> ALLOWED_TEXT_EXTENSIONS = Set.of(
            ".md", ".txt", ".json", ".yaml", ".yml", ".xml", ".py", ".js", ".ts", ".sh", ".java", ".properties", ".css", ".html");

    private static Path resolveWorkspaceRoot(ToolCall call) {
        if (call != null && call.getWorkspaceRoot() != null) {
            return call.getWorkspaceRoot();
        }
        return Path.of("").toAbsolutePath();
    }

    // ==================== ActivateSkillTool ====================

    public static class ActivateSkillTool implements AgentTool {

        private static final Logger log = LoggerFactory.getLogger(ActivateSkillTool.class);

        private final SkillRepository skillRepository;

        public ActivateSkillTool(SkillRepository skillRepository) {
            this.skillRepository = skillRepository;
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.builder()
                    .name("activate_skill")
                    .description("Activate a skill by name. Activated skills provide specialized instructions and resources to the agent. Use to load project-specific or user-level skills.")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"description\":\"Name of the skill to activate\"}},\"required\":[\"name\"]}")
                    .build();
        }

        @Override
        public ToolPolicyDecision policy(ToolCall call) {
            return ToolPolicyDecision.readOnly("激活 Skill 只读 skill 目录", "activate_skill");
        }

        @Override
        public ToolResult call(ToolCall call) {
            long startedAt = System.currentTimeMillis();
            JsonNode input = call.getInput();
            String name = input == null ? "" : input.path("name").asText("").strip();
            if (StringUtils.isBlank(name)) {
                return ToolResult.failure("activate_skill_name_required", "name 不能为空", elapsed(startedAt));
            }

            Path workspaceRoot = resolveWorkspaceRoot(call);
            SkillDescriptor descriptor = skillRepository.resolve(name, workspaceRoot);
            if (descriptor == null) {
                return ToolResult.failure("activate_skill_not_found",
                        "未找到名为 '" + name + "' 的 Skill。可用 skill 列表已注入 System Prompt。", elapsed(startedAt));
            }

            String content = skillRepository.readSkillContent(descriptor);
            String preview = StringUtils.abbreviate(content, 500);

            return ToolResult.success(
                    "skill_activation_pending\n"
                            + "name=" + name + "\n"
                            + "source=" + descriptor.source().name().toLowerCase() + "\n"
                            + "manifestSha256=" + descriptor.manifestSha256() + "\n"
                            + "preview:\n" + preview + "\n",
                    false, elapsed(startedAt));
        }

        private long elapsed(long startedAt) {
            return System.currentTimeMillis() - startedAt;
        }
    }

    // ==================== ReadSkillResourceTool ====================

    public static class ReadSkillResourceTool implements AgentTool {

        private final SkillRepository skillRepository;

        public ReadSkillResourceTool(SkillRepository skillRepository) {
            this.skillRepository = skillRepository;
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.builder()
                    .name("read_skill_resource")
                    .description("Read a text resource from an activated skill's snapshot. Use to access skill-provided scripts, templates, or reference files.")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"skill\":{\"type\":\"string\",\"description\":\"Skill name\"},\"path\":{\"type\":\"string\",\"description\":\"Relative path to the resource within the skill directory\"},\"offset\":{\"type\":\"integer\",\"description\":\"Character offset to start reading from\",\"default\":0},\"maxChars\":{\"type\":\"integer\",\"description\":\"Maximum characters to read\",\"default\":8000}},\"required\":[\"skill\",\"path\"]}")
                    .build();
        }

        @Override
        public ToolPolicyDecision policy(ToolCall call) {
            return ToolPolicyDecision.readOnly("只读 skill 资源", "read_skill_resource");
        }

        @Override
        public ToolResult call(ToolCall call) {
            long startedAt = System.currentTimeMillis();
            JsonNode input = call.getInput();
            String skillName = input == null ? "" : input.path("skill").asText("").strip();
            String path = input == null ? "" : input.path("path").asText("").strip();
            int offset = input == null ? 0 : Math.max(0, input.path("offset").asInt(0));
            int maxChars = input == null ? 8000 : Math.min(20000, Math.max(1, input.path("maxChars").asInt(8000)));

            if (StringUtils.isBlank(skillName)) {
                return ToolResult.failure("read_skill_resource_missing_skill", "skill 不能为空", elapsed(startedAt));
            }
            if (StringUtils.isBlank(path)) {
                return ToolResult.failure("read_skill_resource_missing_path", "path 不能为空", elapsed(startedAt));
            }

            // Validate file extension for text
            String lowerPath = path.toLowerCase();
            boolean allowedExtension = ALLOWED_TEXT_EXTENSIONS.stream().anyMatch(lowerPath::endsWith);
            if (!allowedExtension) {
                return ToolResult.failure("read_skill_resource_bad_extension",
                        "不允许读取该文件类型，仅支持文本文件", elapsed(startedAt));
            }

            Path workspaceRoot = resolveWorkspaceRoot(call);
            SkillDescriptor descriptor = skillRepository.resolve(skillName, workspaceRoot);
            if (descriptor == null) {
                return ToolResult.failure("read_skill_resource_not_found",
                        "未找到名为 '" + skillName + "' 的 Skill", elapsed(startedAt));
            }

            String content = skillRepository.readResourceText(descriptor, path, offset, maxChars);
            if (content.isEmpty()) {
                return ToolResult.failure("read_skill_resource_empty",
                        "资源为空或无法读取: " + path, elapsed(startedAt));
            }

            return ToolResult.success(content, content.length() >= maxChars, elapsed(startedAt));
        }

        private long elapsed(long startedAt) {
            return System.currentTimeMillis() - startedAt;
        }
    }

    // ==================== CopySkillResourceTool ====================

    public static class CopySkillResourceTool implements AgentTool {

        private static final Logger log = LoggerFactory.getLogger(CopySkillResourceTool.class);

        private final SkillRepository skillRepository;

        public CopySkillResourceTool(SkillRepository skillRepository) {
            this.skillRepository = skillRepository;
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.builder()
                    .name("copy_skill_resource")
                    .description("Copy a binary or text resource from an activated skill's snapshot to the workspace. Scripts must be copied before execution.")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"skill\":{\"type\":\"string\",\"description\":\"Skill name\"},\"path\":{\"type\":\"string\",\"description\":\"Relative path to the resource within the skill directory\"},\"destination\":{\"type\":\"string\",\"description\":\"Destination path relative to workspace root\"},\"overwrite\":{\"type\":\"boolean\",\"description\":\"Whether to overwrite existing file\",\"default\":false}},\"required\":[\"skill\",\"path\",\"destination\"]}")
                    .build();
        }

        @Override
        public ToolPolicyDecision policy(ToolCall call) {
            return ToolPolicyDecision.writeConfirm("复制 skill 资源到工作区需要确认", "copy_skill_resource");
        }

        @Override
        public ToolResult call(ToolCall call) {
            long startedAt = System.currentTimeMillis();
            JsonNode input = call.getInput();
            String skillName = input == null ? "" : input.path("skill").asText("").strip();
            String path = input == null ? "" : input.path("path").asText("").strip();
            String destination = input == null ? "" : input.path("destination").asText("").strip();
            boolean overwrite = input != null && input.path("overwrite").asBoolean(false);

            if (StringUtils.isBlank(skillName) || StringUtils.isBlank(path) || StringUtils.isBlank(destination)) {
                return ToolResult.failure("copy_skill_resource_missing_params",
                        "skill, path, destination 均为必填", elapsed(startedAt));
            }

            Path workspaceRoot = resolveWorkspaceRoot(call);
            SkillDescriptor descriptor = skillRepository.resolve(skillName, workspaceRoot);
            if (descriptor == null) {
                return ToolResult.failure("copy_skill_resource_not_found",
                        "未找到名为 '" + skillName + "' 的 Skill", elapsed(startedAt));
            }

            Path destPath = workspaceRoot.resolve(destination).normalize();
            if (!destPath.startsWith(workspaceRoot.normalize())) {
                return ToolResult.failure("copy_skill_resource_path_escape",
                        "destination 必须在工作区内", elapsed(startedAt));
            }

            byte[] content = skillRepository.readResourceBytes(descriptor, path);
            if (content.length == 0 && !path.endsWith("/")) {
                return ToolResult.failure("copy_skill_resource_empty",
                        "资源为空或无法读取: " + path, elapsed(startedAt));
            }

            try {
                if (Files.exists(destPath) && !overwrite) {
                    return ToolResult.failure("copy_skill_resource_exists",
                            "destination 已存在，如需覆盖请设置 overwrite=true: " + destination, elapsed(startedAt));
                }
                Files.createDirectories(destPath.getParent());
                if (overwrite) {
                    Files.write(destPath, content);
                } else {
                    Files.copy(new java.io.ByteArrayInputStream(content), destPath, StandardCopyOption.REPLACE_EXISTING);
                }
                return ToolResult.success("copied " + path + " -> " + destination + " (" + content.length + " bytes)",
                        false, elapsed(startedAt));
            } catch (IOException e) {
                log.warn("Failed to copy skill resource: {} -> {}", path, destination, e);
                return ToolResult.failure("copy_skill_resource_io_error", e.getMessage(), elapsed(startedAt));
            }
        }

        private long elapsed(long startedAt) {
            return System.currentTimeMillis() - startedAt;
        }
    }
}
