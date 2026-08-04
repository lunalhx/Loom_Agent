package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.agent.adapter.port.DelegateRunner;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.springframework.stereotype.Component;

/**
 * loom-code {@code delegate}: ask a bounded read-only child agent to
 * investigate. The actual child spawn is delegated to a {@link DelegateRunner}.
 */
@Component
public class DelegateTool implements AgentTool {

    private final DelegateRunner delegateRunner;

    public DelegateTool(DelegateRunner delegateRunner) {
        this.delegateRunner = delegateRunner;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("delegate")
                .description("Ask a bounded read-only child agent to investigate.")
                .inputSchema("{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{" +
                        "\"task\":{\"type\":\"string\",\"minLength\":1,\"description\":\"child task\"}," +
                        "\"max_steps\":{\"type\":\"integer\",\"minimum\":1,\"default\":3,\"description\":\"max child steps\"}" +
                        "}," +
                        "\"required\":[\"task\"]," +
                        "\"additionalProperties\":false" +
                        "}")
                .risky(false)
                .build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        String task = text(call, "task", null);
        int maxSteps = intValue(call, "max_steps", 3);
        if (task == null || task.isBlank()) {
            return failure("task must not be empty", startedAt);
        }
        try {
            String result = delegateRunner.delegate(task, maxSteps);
            return ToolResult.success(result, false, elapsed(startedAt));
        } catch (Exception e) {
            return failure(e.getMessage(), startedAt);
        }
    }

    private String text(ToolCall call, String key, String def) {
        if (call.getInput() == null || !call.getInput().has(key) || call.getInput().path(key).isNull()) {
            return def;
        }
        return call.getInput().path(key).asText(def);
    }

    private int intValue(ToolCall call, String key, int def) {
        if (call.getInput() == null || !call.getInput().has(key) || call.getInput().path(key).isNull()) {
            return def;
        }
        return call.getInput().path(key).asInt(def);
    }

    private ToolResult failure(String message, long startedAt) {
        return ToolResult.failure("delegate_failed", message, elapsed(startedAt));
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
