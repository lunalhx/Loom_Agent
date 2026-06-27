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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class RunShellTool extends FileSystemToolSupport implements AgentTool {

    private static final Set<String> READ_ONLY_COMMANDS = Set.of("pwd", "ls", "rg");
    private final CommandExecutor commandExecutor;

    public RunShellTool(AgentRuntimeProperties properties) {
        super(properties);
        this.commandExecutor = new LocalCommandExecutor();
    }

    @Autowired
    public RunShellTool(AgentRuntimeProperties properties, WorkspacePort workspacePort, CommandExecutor commandExecutor) {
        super(properties, workspacePort);
        this.commandExecutor = commandExecutor;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("run_shell")
                .description("在工作区进程级沙箱内执行允许的只读命令或测试命令；写入类命令必须人工确认，高危命令会被拦截")
                .inputSchema("{\"command\":\"必填命令字符串\",\"cwd\":\"相对工作目录，默认 .\",\"timeoutMs\":\"默认 AGENT_SHELL_TIMEOUT_MS\"}")
                .build();
    }

    @Override
    public ToolPolicyDecision policy(ToolCall call) {
        String command = text(call.getInput(), "command", "");
        List<String> tokens;
        try {
            tokens = splitCommand(command);
            validatePathLikeArguments(tokens);
        } catch (Exception e) {
            return ToolPolicyDecision.highRiskDeny("命令包含不允许的 shell 语法或路径：" + e.getMessage(), command);
        }
        if (tokens.isEmpty()) {
            return ToolPolicyDecision.highRiskDeny("command 不能为空", command);
        }
        String executable = tokens.get(0);
        if ("rm".equals(executable) || "rmdir".equals(executable)) {
            return ToolPolicyDecision.highRiskDeny(
                    "禁止通过 shell 执行 " + executable + "，请使用 delete_files 工具",
                    command);
        }
        if (!allowedShellCommands().contains(executable)) {
            return ToolPolicyDecision.highRiskDeny("命令不在允许列表：" + executable, command);
        }
        if ("mvn".equals(executable) || "./mvnw".equals(executable)) {
            if (isMavenTestCommand(tokens)) {
                return ToolPolicyDecision.writeConfirm("Maven 测试会写入 target 等构建目录，需要人工确认", command);
            }
            return ToolPolicyDecision.highRiskDeny("第一版仅允许执行 Maven 测试命令", command);
        }
        if ("git".equals(executable)) {
            return gitPolicy(tokens, command);
        }
        if (READ_ONLY_COMMANDS.contains(executable)) {
            return ToolPolicyDecision.readOnly("允许的只读 shell 命令", command);
        }
        return ToolPolicyDecision.highRiskDeny("未分类 shell 命令默认拦截", command);
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            ToolPolicyDecision policy = policy(call);
            if (policy.getPermissionLevel() == ToolPermissionLevel.HIGH_RISK_DENY) {
                return failure("shell_command_rejected", policy.getRiskReason(), startedAt);
            }
            List<String> tokens = splitCommand(text(call.getInput(), "command", ""));
            Path cwd = resolvePath(call, "cwd", ".");
            if (!Files.isDirectory(cwd)) {
                return failure("not_directory", "cwd 不是目录：" + relative(call, cwd), startedAt);
            }
            long requestedTimeoutMs = call.getInput() == null
                    ? properties.getShellTimeoutMs()
                    : call.getInput().path("timeoutMs").asLong(properties.getShellTimeoutMs());
            long timeoutMs = Math.min(Math.max(1L, requestedTimeoutMs), properties.getShellTimeoutMs());
            return commandExecutor.run(tokens, cwd, timeoutMs, properties.getShellMaxOutputChars(), startedAt);
        } catch (Exception e) {
            return failure("run_shell_failed", e.getMessage(), startedAt);
        }
    }

    private ToolPolicyDecision gitPolicy(List<String> tokens, String command) {
        if (tokens.size() < 2) {
            return ToolPolicyDecision.highRiskDeny("git 子命令不能为空", command);
        }
        String operation = tokens.get(1).toLowerCase(Locale.ROOT);
        if ("rm".equals(operation)) {
            return ToolPolicyDecision.highRiskDeny("禁止通过 shell 执行 git rm，请使用 delete_files 工具", command);
        }
        return GitRiskClassifier.classify(tokens, command);
    }

    private boolean isMavenTestCommand(List<String> tokens) {
        return tokens.stream().anyMatch(token ->
                "test".equals(token)
                        || "verify".equals(token)
                        || token.endsWith(":test")
                        || token.startsWith("-Dtest="));
    }

    private List<String> splitCommand(String command) {
        if (StringUtils.isBlank(command)) {
            throw new IllegalArgumentException("command 不能为空");
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (ch == '\n' || ch == '\r') {
                throw new IllegalArgumentException("禁止换行");
            }
            if (!singleQuoted && ch == '"') {
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (!doubleQuoted && ch == '\'') {
                singleQuoted = !singleQuoted;
                continue;
            }
            if (!singleQuoted && !doubleQuoted) {
                if (Character.isWhitespace(ch)) {
                    addToken(tokens, current);
                    continue;
                }
                if (isShellMeta(ch)) {
                    throw new IllegalArgumentException("禁止 shell 元字符：" + ch);
                }
            }
            current.append(ch);
        }
        if (singleQuoted || doubleQuoted) {
            throw new IllegalArgumentException("引号未闭合");
        }
        addToken(tokens, current);
        return tokens;
    }

    private void addToken(List<String> tokens, StringBuilder current) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    private boolean isShellMeta(char ch) {
        return "|&;<>`$(){}[]*?~\\".indexOf(ch) >= 0;
    }

    private void validatePathLikeArguments(List<String> tokens) {
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String lower = token.toLowerCase(Locale.ROOT);
            if (token.startsWith("/") || token.contains("..")) {
                throw new IllegalArgumentException("禁止绝对路径或上级目录：" + token);
            }
            if (lower.contains(".git") || lower.contains(".idea") || lower.contains("node_modules")
                    || lower.contains("docs/env/.env") || lower.endsWith(".key")
                    || lower.endsWith(".pem") || lower.endsWith(".p12")) {
                throw new IllegalArgumentException("路径被安全策略拦截：" + token);
            }
        }
    }

    private List<String> allowedShellCommands() {
        return properties.getAllowedShellCommands() == null ? List.of() : properties.getAllowedShellCommands();
    }

}
