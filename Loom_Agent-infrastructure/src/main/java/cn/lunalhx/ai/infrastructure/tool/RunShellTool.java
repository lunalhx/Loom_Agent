package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
import cn.lunalhx.ai.domain.tool.adapter.port.CommandExecutor;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.BackgroundLaunchMode;
import cn.lunalhx.ai.domain.tool.model.BackgroundShellTask;
import cn.lunalhx.ai.domain.tool.model.ShellOutputLimits;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class RunShellTool extends FileSystemToolSupport implements AgentTool {

    private static final Set<String> PATH_WHITELIST = Set.of("./mvnw", "mvnw");
    private static final long DEFAULT_FOREGROUND_YIELD_MS = 10_000;
    private static final long MAX_FOREGROUND_YIELD_MS = 30_000;

    private final CommandExecutor commandExecutor;
    private final BackgroundProcessManager backgroundProcessManager;
    private final BackgroundShellTaskRepository taskRepository;

    public RunShellTool(AgentRuntimeProperties properties, WorkspacePort workspacePort,
                        CommandExecutor commandExecutor, BackgroundProcessManager backgroundProcessManager,
                        BackgroundShellTaskRepository taskRepository) {
        super(properties, workspacePort);
        this.commandExecutor = commandExecutor;
        this.backgroundProcessManager = backgroundProcessManager;
        this.taskRepository = taskRepository;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("run_shell")
                .description("在工作区沙箱内执行已分类命令。何时使用：构建、测试、受支持的 CLI 命令。何时不要使用：Git 操作优先用 git_op，文件搜索用 find_files/code_search，文件删除用 delete_files。限制：禁止 shell 解释器、管道、重定向等元字符；只读命令自动放行，写命令需确认，高危命令需高危确认。支持后台执行：设置 runInBackground=true 立即后台，或命令超过 foregroundYieldMs 未结束自动转后台。后台任务可通过 shell_task 工具查询、读取输出和取消。")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\",\"minLength\":1,\"description\":\"要执行的 shell 命令\"},\"cwd\":{\"type\":\"string\",\"default\":\".\",\"description\":\"相对工作目录\"},\"timeoutMs\":{\"type\":\"integer\",\"minimum\":1,\"default\":120000,\"description\":\"超时毫秒，受系统配置上限限制\"},\"runInBackground\":{\"type\":\"boolean\",\"default\":false,\"description\":\"是否显式要求后台执行，不等待 yield 窗口\"},\"foregroundYieldMs\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":30000,\"default\":10000,\"description\":\"前台等待毫秒，超时未完成自动转后台\"}},\"required\":[\"command\"],\"additionalProperties\":false}")
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

        if ("env".equals(executable)) {
            String actualCmd = extractEnvCommand(tokens);
            if (actualCmd != null) {
                executable = actualCmd;
                List<String> newTokens = new ArrayList<>();
                newTokens.add(executable);
                for (int i = 1; i < tokens.size(); i++) {
                    String t = tokens.get(i);
                    if (!t.contains("=")) {
                        newTokens.addAll(tokens.subList(i, tokens.size()));
                        break;
                    }
                }
                tokens = newTokens;
            } else {
                return ToolPolicyDecision.readOnly("列出环境变量", command);
            }
        }

        if (isShellInterpreter(executable)) {
            return ToolPolicyDecision.highRiskDeny(
                    "禁止通过 shell 解释器执行命令，请直接调用目标命令", command);
        }

        if (executable.contains("/")) {
            String basename = executable.substring(executable.lastIndexOf('/') + 1);
            if (!PATH_WHITELIST.contains(executable) && !PATH_WHITELIST.contains(basename)) {
                if (isDenied(basename)) {
                    return denyForCommand(basename, command);
                }
                return ToolPolicyDecision.highRiskConfirm(
                        "路径型可执行文件视为高危操作：" + executable, command);
            }
        }

        if (isDenied(executable)) {
            return denyForCommand(executable, command);
        }

        if ("git".equals(executable)) {
            return gitPolicy(tokens, command);
        }

        if ("mvn".equals(executable) || "./mvnw".equals(executable)) {
            if (isMavenTestCommand(tokens)) {
                return ToolPolicyDecision.writeConfirm("Maven 测试会写入 target 等构建目录，需要人工确认", command);
            }
            return ToolPolicyDecision.highRiskConfirm("构建会执行插件代码并写入 target，属高危操作", command);
        }

        if (shellCommands().getReadOnly().contains(executable)) {
            return ToolPolicyDecision.readOnly("允许的只读 shell 命令", command);
        }

        if (shellCommands().getWrite().contains(executable)) {
            return ToolPolicyDecision.writeConfirm("写命令需要人工确认", command);
        }

        if (shellCommands().getHighRisk().contains(executable)) {
            return ToolPolicyDecision.highRiskConfirm("高危命令需要高危确认", command);
        }

        String unknownLevel = shellCommands().getUnknownLevel();
        if ("HIGH_RISK_DENY".equalsIgnoreCase(unknownLevel)) {
            return ToolPolicyDecision.highRiskDeny("未分类 shell 命令：" + executable, command);
        }
        if ("HIGH_RISK_CONFIRM".equalsIgnoreCase(unknownLevel)) {
            return ToolPolicyDecision.highRiskConfirm("未分类命令需要高危确认：" + executable, command);
        }
        return ToolPolicyDecision.writeConfirm("未分类命令需要人工确认：" + executable, command);
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

            boolean runInBackground = call.getInput() != null
                    && call.getInput().path("runInBackground").asBoolean(false);

            long requestedTimeoutMs = call.getInput() == null
                    ? properties.getShellTimeoutMs()
                    : call.getInput().path("timeoutMs").asLong(properties.getShellTimeoutMs());
            long maxTimeoutMs = properties.getShellMaxTimeoutMs() > 0
                    ? properties.getShellMaxTimeoutMs() : 600_000L;
            long timeoutMs = Math.min(Math.max(1L, requestedTimeoutMs), maxTimeoutMs);

            long yieldMs = call.getInput() == null ? DEFAULT_FOREGROUND_YIELD_MS
                    : call.getInput().path("foregroundYieldMs").asLong(DEFAULT_FOREGROUND_YIELD_MS);
            yieldMs = Math.min(Math.max(0L, yieldMs), MAX_FOREGROUND_YIELD_MS);

            if (runInBackground && backgroundProcessManager != null) {
                return startBackgroundTask(tokens, cwd, timeoutMs, call, startedAt);
            }

            if (yieldMs <= 0 || backgroundProcessManager == null) {
                return commandExecutor.run(tokens, cwd, timeoutMs,
                        ShellOutputLimits.builder()
                                .maxStdoutChars(properties.getShellMaxOutputChars())
                                .maxStderrChars(properties.getShellMaxStderrChars())
                                .build(),
                        startedAt);
            }

            // Try sync with yield
            if (timeoutMs > yieldMs) {
                timeoutMs = yieldMs;
            }
            ToolResult result = commandExecutor.run(tokens, cwd, timeoutMs,
                    ShellOutputLimits.builder()
                            .maxStdoutChars(properties.getShellMaxOutputChars())
                            .maxStderrChars(properties.getShellMaxStderrChars())
                            .build(),
                    startedAt);

            if ("command_timeout".equals(result.getErrorCode())) {
                return startBackgroundTask(tokens, cwd, requestedTimeoutMs, call, startedAt);
            }
            return result;
        } catch (Exception e) {
            return failure("run_shell_failed", e.getMessage(), startedAt);
        }
    }

    private ToolResult startBackgroundTask(List<String> tokens, Path cwd, long timeoutMs,
                                            ToolCall call, long startedAt) {
        BackgroundProcessManager.BackgroundStartResult bgResult = backgroundProcessManager.startBackground(
                tokens, cwd, timeoutMs,
                call.getRunId(), call.getConversationId(),
                call.getWorkspace() != null ? call.getWorkspace().getDisplayName() : null,
                BackgroundLaunchMode.EXPLICIT);

        if (!bgResult.started()) {
            return failure(bgResult.errorCode(), bgResult.message(), startedAt);
        }

        BackgroundShellTask task = bgResult.task();
        if (taskRepository != null) {
            taskRepository.save(task);
        }

        long elapsedMs = System.currentTimeMillis() - startedAt;
        String observation = "后台任务已启动\n"
                + "task_id: " + task.getTaskId() + "\n"
                + "command: " + task.getCommand() + "\n"
                + "cwd: " + task.getCwd() + "\n"
                + "状态: " + task.getStatus() + "\n"
                + "使用 shell_task 工具查询结果、读取输出或取消任务";

        return ToolResult.builder()
                .success(true)
                .observation(observation)
                .truncated(false)
                .elapsedMs(elapsedMs)
                .build();
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

    private boolean isShellInterpreter(String executable) {
        return Set.of("sh", "bash", "zsh", "dash", "ksh", "csh", "fish", "ash", "exec", "eval", "source")
                .contains(executable);
    }

    private String extractEnvCommand(List<String> tokens) {
        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (!t.contains("=")) {
                return t;
            }
        }
        return null;
    }

    private boolean isDenied(String executable) {
        return shellCommands().getDeny().contains(executable);
    }

    private ToolPolicyDecision denyForCommand(String executable, String command) {
        if ("rm".equals(executable) || "rmdir".equals(executable)) {
            return ToolPolicyDecision.highRiskDeny(
                    "禁止通过 shell 执行 " + executable + "，请使用 delete_files 工具", command);
        }
        if ("find".equals(executable) || "python".equals(executable) || "python3".equals(executable)) {
            return ToolPolicyDecision.highRiskDeny(
                    "禁止通过 shell 执行 " + executable + "，请使用结构化工具 find_files", command);
        }
        return ToolPolicyDecision.highRiskDeny("禁止通过 shell 执行 " + executable, command);
    }

    private AgentRuntimeProperties.ShellCommandProperties shellCommands() {
        AgentRuntimeProperties.ShellCommandProperties sc = properties.getShellCommands();
        return sc != null ? sc : new AgentRuntimeProperties.ShellCommandProperties();
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

}
