package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class GitOpTool extends FileSystemToolSupport implements AgentTool {

    private static final Set<String> READ_ONLY_OPS = Set.of("status", "diff", "log");
    private static final Set<String> WRITE_OPS = Set.of("add", "commit");
    private static final Set<String> HIGH_RISK_OPS = Set.of("push", "reset", "clean", "rebase", "checkout");

    public GitOpTool(AgentRuntimeProperties properties) {
        super(properties);
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("git_op")
                .description("执行受限 Git 操作；status/diff/log 自动放行，add/commit 需人工确认，高危操作被拦截")
                .inputSchema("{\"operation\":\"status|diff|log|add|commit\",\"path\":\"add/diff 可选相对路径\",\"message\":\"commit 必填\",\"limit\":\"log 默认 10\"}")
                .build();
    }

    @Override
    public ToolPolicyDecision policy(ToolCall call) {
        String operation = operation(call);
        if (READ_ONLY_OPS.contains(operation)) {
            return ToolPolicyDecision.readOnly("Git 只读操作自动放行", "git " + operation);
        }
        if (WRITE_OPS.contains(operation)) {
            return ToolPolicyDecision.writeConfirm("Git 暂存或提交会修改仓库状态，需要人工确认", "git " + operation + previewPath(call));
        }
        if (HIGH_RISK_OPS.contains(operation)) {
            return ToolPolicyDecision.highRiskDeny("高危 Git 操作已被拦截：" + operation, "git " + operation);
        }
        return ToolPolicyDecision.highRiskDeny("不支持的 Git 操作：" + operation, "git " + operation);
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            ToolPolicyDecision policy = policy(call);
            if (policy.getPermissionLevel() == ToolPermissionLevel.HIGH_RISK_DENY) {
                return failure("git_op_rejected", policy.getRiskReason(), startedAt);
            }
            List<String> command = command(call);
            return SandboxProcessRunner.run(command, workspaceRoot(), properties.getShellTimeoutMs(), properties.getShellMaxOutputChars(), startedAt);
        } catch (Exception e) {
            return failure("git_op_failed", e.getMessage(), startedAt);
        }
    }

    private List<String> command(ToolCall call) throws Exception {
        String operation = operation(call);
        List<String> command = new ArrayList<>();
        command.add("git");
        switch (operation) {
            case "status" -> {
                command.add("status");
                command.add("--short");
            }
            case "diff" -> {
                command.add("diff");
                addPathArg(command, call);
            }
            case "log" -> {
                int limit = Math.max(1, Math.min(50, integer(call.getInput(), "limit", 10)));
                command.add("log");
                command.add("--oneline");
                command.add("-n");
                command.add(String.valueOf(limit));
            }
            case "add" -> {
                command.add("add");
                addPathArg(command, call);
            }
            case "commit" -> {
                String message = text(call.getInput(), "message", "");
                if (StringUtils.isBlank(message)) {
                    throw new IllegalArgumentException("message 不能为空");
                }
                command.add("commit");
                command.add("-m");
                command.add(message);
            }
            default -> throw new IllegalArgumentException("不支持的 Git 操作：" + operation);
        }
        return command;
    }

    private void addPathArg(List<String> command, ToolCall call) throws Exception {
        String rawPath = text(call.getInput(), "path", null);
        if (StringUtils.isBlank(rawPath)) {
            return;
        }
        Path path = resolvePath(call.getInput(), "path", null);
        command.add("--");
        command.add(relative(path));
    }

    private String operation(ToolCall call) {
        return text(call.getInput(), "operation", "").toLowerCase(Locale.ROOT);
    }

    private String previewPath(ToolCall call) {
        String path = text(call.getInput(), "path", "");
        return StringUtils.isBlank(path) ? "" : " path=" + path;
    }

}
