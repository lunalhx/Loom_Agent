package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentPlanItemStatus;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class TodoWriteTool implements AgentTool {

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("todo_write")
                .description("更新当前 Agent 计划和子任务状态，不修改工作区文件")
                .inputSchema("{\"todos\":[{\"id\":\"可选任务ID\",\"content\":\"任务内容\",\"status\":\"pending|in_progress|completed|blocked|skipped\",\"evidence\":\"可选完成证据\",\"blocker\":\"可选阻塞原因\"}]}")
                .build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            JsonNode todos = call.getInput() == null ? null : call.getInput().path("todos");
            if (todos == null || !todos.isArray()) {
                return ToolResult.failure("invalid_todos", "todos 必须是数组", elapsed(startedAt));
            }
            for (int i = 0; i < todos.size(); i++) {
                JsonNode todo = todos.get(i);
                if (StringUtils.isBlank(todo.path("content").asText(null))) {
                    return ToolResult.failure("invalid_todos", "todos[" + i + "].content 不能为空", elapsed(startedAt));
                }
                AgentPlanItemStatus.from(todo.path("status").asText("pending"));
            }
            return ToolResult.success("todo_write accepted " + todos.size() + " tasks", false, elapsed(startedAt));
        } catch (Exception e) {
            return ToolResult.failure("invalid_todos", e.getMessage(), elapsed(startedAt));
        }
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

}
