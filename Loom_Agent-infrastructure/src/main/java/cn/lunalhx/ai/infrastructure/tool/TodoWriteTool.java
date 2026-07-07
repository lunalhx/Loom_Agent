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
                .description("更新当前 Agent 计划和子任务状态，不修改工作区文件。创建任务时如果目标文件(targets)或验证命令(verification.command)与已有任务相同，应使用已有 id 更新，不要换 wording 创建重复任务。")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"todos\":{\"type\":\"array\",\"minItems\":1,\"items\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\",\"description\":\"任务ID，更新时用于匹配已有任务（不再支持用 content 匹配更新）\"},\"content\":{\"type\":\"string\",\"minLength\":1,\"description\":\"任务内容，创建新任务时必填；不可用于匹配已有任务\"},\"status\":{\"type\":\"string\",\"enum\":[\"pending\",\"in_progress\",\"completed\",\"blocked\",\"skipped\"]},\"kind\":{\"type\":\"string\",\"enum\":[\"inspect\",\"edit\",\"verify\"],\"description\":\"任务类型\"},\"targets\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"涉及的工作区相对文件路径\"},\"evidence\":{\"type\":\"string\",\"description\":\"可选完成证据\"},\"blocker\":{\"type\":\"string\",\"description\":\"可选阻塞原因\"},\"verification\":{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"},\"passed\":{\"type\":\"boolean\"},\"exitCode\":{\"type\":\"integer\"},\"summary\":{\"type\":\"string\"}},\"additionalProperties\":false}},\"required\":[\"status\"],\"additionalProperties\":false}}},\"required\":[\"todos\"],\"additionalProperties\":false}")
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
                String todoId = todo.path("id").asText(null);
                String todoContent = todo.path("content").asText(null);
                if (StringUtils.isBlank(todoId) && StringUtils.isBlank(todoContent)) {
                    return ToolResult.failure("invalid_todos",
                        "todos[" + i + "]: 创建新任务需要提供 content，或提供 id 更新已有任务（不要用 content 匹配已有任务）",
                        elapsed(startedAt));
                }
                if (!todo.has("status")) {
                    return ToolResult.failure(
                            "invalid_todos",
                            "todos[" + i + "].status 不能为空",
                            elapsed(startedAt));
                }
                AgentPlanItemStatus.from(todo.path("status").asText());
                if (StringUtils.isBlank(todoId)) {
                    String kind = todo.path("kind").asText(null);
                    if (StringUtils.isBlank(kind)
                            || !java.util.Set.of("inspect", "edit", "verify").contains(kind)) {
                        return ToolResult.failure(
                                "invalid_todos",
                                "todos[" + i + "].kind 新建时必须提供，只能是 inspect、edit 或 verify",
                                elapsed(startedAt));
                    }
                    if ("edit".equals(kind)
                            && (!todo.path("targets").isArray()
                            || todo.path("targets").isEmpty())) {
                        return ToolResult.failure(
                                "invalid_todos",
                                "todos[" + i + "] kind=edit 时 targets 不能为空",
                                elapsed(startedAt));
                    }
                }
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
