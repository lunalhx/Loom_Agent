package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;
import cn.lunalhx.ai.domain.memory.model.valobj.MemorySourceType;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryStatus;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryType;
import cn.lunalhx.ai.domain.memory.service.WorkspaceKeyUtil;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class MemorySaveTool implements AgentTool {

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_BODY_LENGTH = 10_000;

    private final AgentMemoryRepository memoryRepository;

    public MemorySaveTool(AgentMemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("memory_save")
                .description("保存一条长期记忆，供后续对话使用。type: PREFERENCE/WORKFLOW/PROJECT/REFERENCE/PITFALL")
                .inputSchema("{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{" +
                        "\"type\":{\"type\":\"string\",\"enum\":[\"PREFERENCE\",\"WORKFLOW\",\"PROJECT\",\"REFERENCE\",\"PITFALL\"]}," +
                        "\"title\":{\"type\":\"string\",\"maxLength\":" + MAX_TITLE_LENGTH + "}," +
                        "\"body\":{\"type\":\"string\",\"maxLength\":" + MAX_BODY_LENGTH + "}," +
                        "\"importance\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100}" +
                        "}," +
                        "\"required\":[\"type\",\"title\",\"body\"]," +
                        "\"additionalProperties\":false" +
                        "}")
                .build();
    }

    @Override
    public ToolPolicyDecision policy(ToolCall call) {
        return ToolPolicyDecision.builder()
                .permissionLevel(ToolPermissionLevel.PERSISTENT_STATE_WRITE)
                .riskReason("记忆保存需要写入持久化存储")
                .build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            JsonNode input = call.getInput();
            if (input == null) {
                return ToolResult.failure("invalid_input", "input is null", elapsed(startedAt));
            }
            MemoryType type = MemoryType.valueOf(input.path("type").asText());
            String title = truncate(input.path("title").asText(""), MAX_TITLE_LENGTH);
            String body = truncate(input.path("body").asText(""), MAX_BODY_LENGTH);
            int importance = input.path("importance").asInt(50);
            if (importance < 0) importance = 0;
            if (importance > 100) importance = 100;

            String workspacePath = call.getWorkspace().getLocation() != null
                    ? call.getWorkspace().getLocation()
                    : "";
            String workspaceKey = WorkspaceKeyUtil.compute(workspacePath);
            String contentHash = sha256(title + "|" + body);

            int activeCount = memoryRepository.countActive(workspaceKey);
            if (activeCount >= 200) {
                return ToolResult.failure("capacity_full",
                        "当前 workspace 记忆已达上限（200），请清理后再保存", elapsed(startedAt));
            }

            AgentMemory memory = AgentMemory.builder()
                    .memoryId(UUID.randomUUID().toString())
                    .workspaceKey(workspaceKey)
                    .workspacePath(workspacePath)
                    .type(type)
                    .title(title)
                    .summary(truncate(body, 200))
                    .body(body)
                    .status(MemoryStatus.ACTIVE)
                    .pinned(false)
                    .importance(importance)
                    .sourceType(MemorySourceType.EXPLICIT_SAVE)
                    .contentHash(contentHash)
                    .version(0)
                    .usageCount(0)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            AgentMemory saved = memoryRepository.save(memory);
            return ToolResult.success("记忆已保存: " + saved.getMemoryId(), false, elapsed(startedAt));
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("invalid_type", "不支持的记忆类型: " + e.getMessage(), elapsed(startedAt));
        } catch (Exception e) {
            return ToolResult.failure("memory_save_failed", e.getMessage(), elapsed(startedAt));
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
