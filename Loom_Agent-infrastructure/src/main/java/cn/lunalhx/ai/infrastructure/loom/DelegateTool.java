package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.agent.adapter.port.DelegateRunner;
import cn.lunalhx.ai.domain.agent.model.entity.DelegateRequest;
import cn.lunalhx.ai.domain.agent.model.entity.DelegateResult;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * loom-code {@code delegate}: ask a bounded read-only child agent to
 * investigate. The actual child spawn is delegated to a {@link DelegateRunner};
 * the child inherits the Runtime-created lineage and authority boundary.
 */
@Component
public class DelegateTool implements AgentTool {

    private static final int MAX_PARENT_SUMMARY_CHARS = 300;

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
                        "\"max_steps\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":3,\"default\":3,\"description\":\"max child steps\"}" +
                        "}," +
                        "\"required\":[\"task\"]," +
                        "\"additionalProperties\":false" +
                        "}")
                .capabilityEnvelope(ToolCapabilityEnvelope.repositoryRead())
                .build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        String task = text(call, "task", null);
        int maxSteps = Math.min(3, intValue(call, "max_steps", 3));
        if (task == null || task.isBlank()) {
            return failure("task must not be empty", startedAt);
        }
        try {
            DelegateRequest request = Objects.requireNonNull(call.getDelegateRequest(),
                    "delegate call is missing its Runtime-created request");
            request.setTask(task);
            request.setRequestedMaxSteps(maxSteps);
            request.setParentSummary(call.getRecentSummary() == null
                    ? "" : abbreviate(call.getRecentSummary(), MAX_PARENT_SUMMARY_CHARS));
            DelegateResult result = delegateRunner.delegate(request);
            if (result == null) {
                return failure("delegate returned no structured result", startedAt);
            }
            String outcome = result.getSafeOutcome() == null || result.getSafeOutcome().isBlank()
                    ? "(empty)" : result.getSafeOutcome();
            ToolResult toolResult = result.isSuccessful()
                    ? ToolResult.success(outcome, false, elapsed(startedAt))
                    : ToolResult.failure("delegate_failed", outcome, elapsed(startedAt));
            toolResult.setDelegateResult(result);
            return toolResult;
        } catch (Exception e) {
            return failure(e.getMessage(), startedAt);
        }
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
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
