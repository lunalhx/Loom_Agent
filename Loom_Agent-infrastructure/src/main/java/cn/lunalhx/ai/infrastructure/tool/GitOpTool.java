package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.CommandExecutor;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class GitOpTool extends FileSystemToolSupport implements AgentTool {

    private final CommandExecutor commandExecutor;

    public GitOpTool(AgentRuntimeProperties properties) {
        super(properties);
        this.commandExecutor = new LocalCommandExecutor();
    }

    @Autowired
    public GitOpTool(AgentRuntimeProperties properties, WorkspacePort workspacePort, CommandExecutor commandExecutor) {
        super(properties, workspacePort);
        this.commandExecutor = commandExecutor;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("git_op")
                .description("执行受限 Git 操作；status/diff/log 自动放行，add/commit 需普通审批，push/reset/clean/rebase/checkout 需高危审批")
                .inputSchema("{\"operation\":\"status|diff|log|add|commit|push|reset|clean|rebase|checkout\",\"path\":\"add/diff/checkout 可选相对路径\",\"message\":\"commit 必填\",\"limit\":\"log 默认 10\",\"branch\":\"checkout/reset/rebase 可选\",\"remote\":\"push 可选 remote\",\"refspec\":\"push 可选 refspec\",\"force\":\"push/reset/rebase 可选\",\"dryRun\":\"clean 可选\"}")
                .build();
    }

    @Override
    public ToolPolicyDecision policy(ToolCall call) {
        String operation = operation(call);
        List<String> tokens = new ArrayList<>();
        tokens.add("git");
        tokens.add(operation);
        try {
            List<String> command = buildCommand(call);
            tokens = command;
        } catch (Exception e) {
            // use minimal tokens
        }
        return GitRiskClassifier.classify(tokens, "git " + operation + previewArgs(call));
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            ToolPolicyDecision policy = policy(call);
            if (policy.getPermissionLevel() == ToolPermissionLevel.HIGH_RISK_DENY) {
                return failure("git_op_rejected", policy.getRiskReason(), startedAt);
            }
            List<String> command = buildCommand(call);
            return commandExecutor.run(command, workspaceRoot(call), properties.getShellTimeoutMs(),
                    properties.getShellMaxOutputChars(), startedAt);
        } catch (Exception e) {
            return failure("git_op_failed", e.getMessage(), startedAt);
        }
    }

    private List<String> buildCommand(ToolCall call) throws Exception {
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
            case "push" -> buildPushCommand(command, call);
            case "reset" -> buildResetCommand(command, call);
            case "clean" -> buildCleanCommand(command, call);
            case "rebase" -> buildRebaseCommand(command, call);
            case "checkout" -> buildCheckoutCommand(command, call);
            default -> throw new IllegalArgumentException("不支持的 Git 操作：" + operation);
        }
        return command;
    }

    private void buildPushCommand(List<String> command, ToolCall call) throws Exception {
        command.add("push");
        String remote = text(call.getInput(), "remote", null);
        String refspec = text(call.getInput(), "refspec", null);
        boolean force = booleanValue(call.getInput(), "force", false);
        if (force) {
            command.add("--force-with-lease");
        }
        if (StringUtils.isNotBlank(remote)) {
            command.add(remote);
        }
        if (StringUtils.isNotBlank(refspec)) {
            command.add(refspec);
        }
    }

    private void buildResetCommand(List<String> command, ToolCall call) throws Exception {
        command.add("reset");
        String branch = text(call.getInput(), "branch", null);
        boolean force = booleanValue(call.getInput(), "force", false);
        if (force) {
            throw new IllegalArgumentException("reset --hard 被永久拦截");
        }
        if (StringUtils.isNotBlank(branch)) {
            command.add(branch);
        } else {
            command.add("HEAD");
        }
    }

    private void buildCleanCommand(List<String> command, ToolCall call) throws Exception {
        boolean dryRun = booleanValue(call.getInput(), "dryRun", true);
        if (dryRun) {
            command.add("clean");
            command.add("--dry-run");
        } else {
            command.add("clean");
            command.add("-f");
        }
    }

    private void buildRebaseCommand(List<String> command, ToolCall call) throws Exception {
        command.add("rebase");
        String branch = text(call.getInput(), "branch", null);
        if (StringUtils.isNotBlank(branch)) {
            command.add(branch);
        }
    }

    private void buildCheckoutCommand(List<String> command, ToolCall call) throws Exception {
        command.add("checkout");
        String branch = text(call.getInput(), "branch", null);
        if (StringUtils.isNotBlank(branch)) {
            if (branch.startsWith("-")) {
                throw new IllegalArgumentException("checkout 不支持标志参数，请使用结构化字段");
            }
            command.add(branch);
        } else {
            String path = text(call.getInput(), "path", null);
            if (StringUtils.isNotBlank(path)) {
                throw new IllegalArgumentException("checkout -- <path> 被永久拦截");
            }
        }
    }

    private void addPathArg(List<String> command, ToolCall call) throws Exception {
        String rawPath = text(call.getInput(), "path", null);
        if (StringUtils.isBlank(rawPath)) {
            return;
        }
        Path path = resolvePath(call, "path", null);
        command.add("--");
        command.add(relative(call, path));
    }

    private boolean booleanValue(com.fasterxml.jackson.databind.JsonNode input, String field, boolean defaultValue) {
        if (input == null || input.isMissingNode() || input.path(field).isMissingNode() || input.path(field).isNull()) {
            return defaultValue;
        }
        return input.path(field).asBoolean(defaultValue);
    }

    private String operation(ToolCall call) {
        return text(call.getInput(), "operation", "").toLowerCase(Locale.ROOT);
    }

    private String previewArgs(ToolCall call) {
        StringBuilder sb = new StringBuilder();
        String path = text(call.getInput(), "path", null);
        String branch = text(call.getInput(), "branch", null);
        String remote = text(call.getInput(), "remote", null);
        String refspec = text(call.getInput(), "refspec", null);
        if (StringUtils.isNotBlank(path)) sb.append(" path=").append(path);
        if (StringUtils.isNotBlank(branch)) sb.append(" branch=").append(branch);
        if (StringUtils.isNotBlank(remote)) sb.append(" remote=").append(remote);
        if (StringUtils.isNotBlank(refspec)) sb.append(" refspec=").append(refspec);
        return sb.toString();
    }
}
