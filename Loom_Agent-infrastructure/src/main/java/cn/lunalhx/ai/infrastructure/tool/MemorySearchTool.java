package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;
import cn.lunalhx.ai.domain.memory.service.WorkspaceKeyUtil;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.List;

public class MemorySearchTool implements AgentTool {

    private static final int MAX_RESULTS = 20;

    private final AgentMemoryRepository memoryRepository;

    public MemorySearchTool(AgentMemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("memory_search")
                .description("搜索长期记忆，返回匹配的记忆条目（只读）")
                .inputSchema("{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{" +
                        "\"query\":{\"type\":\"string\",\"description\":\"搜索关键词\"}," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":" + MAX_RESULTS + ",\"default\":10}" +
                        "}," +
                        "\"required\":[\"query\"]," +
                        "\"additionalProperties\":false" +
                        "}")
                .build();
    }

    @Override
    public ToolPolicyDecision policy(ToolCall call) {
        return ToolPolicyDecision.builder()
                .permissionLevel(ToolPermissionLevel.READ_ONLY)
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
            String query = input.path("query").asText("");
            if (query.isBlank()) {
                return ToolResult.failure("empty_query", "查询关键词不能为空", elapsed(startedAt));
            }
            int limit = input.path("limit").asInt(10);
            if (limit < 1) limit = 1;
            if (limit > MAX_RESULTS) limit = MAX_RESULTS;

            String workspacePath = call.getWorkspace().getLocation() != null
                    ? call.getWorkspace().getLocation()
                    : "";
            String workspaceKey = WorkspaceKeyUtil.compute(workspacePath);

            List<String> keywords = Arrays.stream(query.split("[\\s，,。.!！？?]+"))
                    .filter(w -> w.length() >= 1)
                    .toList();
            if (keywords.isEmpty()) {
                keywords = List.of(query);
            }

            List<AgentMemory> results = memoryRepository.searchByKeywords(workspaceKey, keywords, limit);

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(results.size()).append(" 条记忆：\n\n");
            for (int i = 0; i < results.size(); i++) {
                AgentMemory m = results.get(i);
                sb.append("[").append(i + 1).append("] ")
                        .append(m.getType().name()).append(" | ")
                        .append(m.getTitle()).append('\n');
                sb.append("  ID: ").append(m.getMemoryId()).append('\n');
                sb.append("  Importance: ").append(m.getImportance()).append('\n');
                sb.append("  Summary: ").append(m.getSummary()).append('\n');
                sb.append("  Body: ").append(m.getBody()).append('\n');
                if (m.isPinned()) {
                    sb.append("  [PINNED]\n");
                }
                sb.append('\n');
            }
            return ToolResult.success(sb.toString(), false, elapsed(startedAt));
        } catch (Exception e) {
            return ToolResult.failure("memory_search_failed", e.getMessage(), elapsed(startedAt));
        }
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
