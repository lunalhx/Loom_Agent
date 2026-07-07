package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.CommandExecutor;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.BackgroundLaunchMode;
import cn.lunalhx.ai.domain.tool.model.BackgroundShellTask;
import cn.lunalhx.ai.domain.tool.model.ShellOutputLimits;
import cn.lunalhx.ai.domain.tool.model.ShellCommandAnalysis;
import cn.lunalhx.ai.domain.tool.model.ShellExecutionMode;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RunShellTool extends FileSystemToolSupport implements AgentTool {

    private static final Set<String> PATH_WHITELIST = Set.of("./mvnw", "mvnw");
    private static final long DEFAULT_FOREGROUND_YIELD_MS = 10_000;
    private static final long MAX_FOREGROUND_YIELD_MS = 30_000;

    private final CommandExecutor commandExecutor;
    private final BackgroundProcessManager backgroundProcessManager;

    public RunShellTool(AgentRuntimeProperties properties, WorkspacePort workspacePort,
                        CommandExecutor commandExecutor, BackgroundProcessManager backgroundProcessManager) {
        super(properties, workspacePort);
        this.commandExecutor = commandExecutor;
        this.backgroundProcessManager = backgroundProcessManager;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("run_shell")
                .description("在工作区沙箱内执行已分类命令。何时使用：构建、测试、受支持的 CLI 命令。Maven 测试请直接使用 `mvn -q -o test`，或使用 `mvn test -Dtest=TestClass` 选择测试；管道、重定向、逻辑操作等 shell 语法需高危确认后通过 shell 解释器执行，危险命令（sudo、破坏性删除、管道到解释器、敏感文件）直接拒绝。何时不要使用：Git 操作优先用 git_op，文件搜索用 find_files/code_search，文件删除用 delete_files。只读命令自动放行，写命令需确认，高危命令需高危确认。支持后台执行：设置 runInBackground=true 立即后台，或命令超过 foregroundYieldMs 未结束自动转后台。后台任务可通过 shell_task 工具查询、读取输出和取消。")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\",\"minLength\":1,\"description\":\"要执行的 shell 命令\"},\"cwd\":{\"type\":\"string\",\"default\":\".\",\"description\":\"相对工作目录\"},\"timeoutMs\":{\"type\":\"integer\",\"minimum\":1,\"default\":120000,\"description\":\"超时毫秒，受系统配置上限限制\"},\"runInBackground\":{\"type\":\"boolean\",\"default\":false,\"description\":\"是否显式要求后台执行，不等待 yield 窗口\"},\"foregroundYieldMs\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":30000,\"default\":10000,\"description\":\"前台等待毫秒，超时未完成自动转后台\"}},\"required\":[\"command\"],\"additionalProperties\":false}")
                .build();
    }

    @Override
    public ToolPolicyDecision policy(ToolCall call) {
        String command = text(call.getInput(), "command", "");
        if (command.isBlank()) {
            return ToolPolicyDecision.highRiskDeny("command 不能为空", command);
        }

        ShellCommandAnalysis analysis = ShellCommandAnalyzer.analyze(command);

        if (analysis.isHardDenied()) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("shellAnalysis", analysis);
            return ToolPolicyDecision.builder()
                    .permissionLevel(ToolPermissionLevel.HIGH_RISK_DENY)
                    .riskReason(analysis.getHardDenyReason())
                    .operationPreview(command)
                    .metadata(metadata)
                    .build();
        }

        if (analysis.getExecutionMode() == ShellExecutionMode.SHELL_EXEC) {
            String riskReason = "命令需要 shell 解释器执行" +
                    (analysis.getFeatures() != null && !analysis.getFeatures().isEmpty()
                            ? "，包含: " + analysis.getFeatures() : "");
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("shellAnalysis", analysis);
            return ToolPolicyDecision.builder()
                    .permissionLevel(ToolPermissionLevel.HIGH_RISK_CONFIRM)
                    .riskReason(riskReason)
                    .operationPreview(command)
                    .metadata(metadata)
                    .build();
        }

        // SIMPLE_EXEC path
        List<String> tokens = analysis.getTokens();
        if (tokens == null || tokens.isEmpty()) {
            return ToolPolicyDecision.highRiskDeny("命令解析后为空", command);
        }

        String executable = tokens.get(0);

        // Handle env prefix
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
                return injectAnalysis(ToolPolicyDecision.readOnly("列出环境变量", command), analysis);
            }
        }

        // isShellInterpreter check - still reject shell interpreters (B3)
        if (isShellInterpreter(executable)) {
            return injectAnalysis(ToolPolicyDecision.builder()
                    .permissionLevel(ToolPermissionLevel.HIGH_RISK_CONFIRM)
                    .riskReason("通过 shell 解释器执行命令需要高危确认")
                    .operationPreview(command)
                    .build(), analysis);
        }

        if (executable.contains("/")) {
            String basename = executable.substring(executable.lastIndexOf('/') + 1);
            if (!PATH_WHITELIST.contains(executable) && !PATH_WHITELIST.contains(basename)) {
                if (isDenied(basename)) {
                    return denyForCommand(basename, command);
                }
                return injectAnalysis(ToolPolicyDecision.highRiskConfirm(
                        "路径型可执行文件视为高危操作：" + executable, command), analysis);
            }
        }

        if (isDenied(executable)) {
            return denyForCommand(executable, command);
        }

        if ("git".equals(executable)) {
            return injectAnalysis(gitPolicy(tokens, command), analysis);
        }

        if ("mvn".equals(executable) || "./mvnw".equals(executable)
                || "mvnw".equals(executable)) {
            return injectAnalysis(mavenPolicy(tokens, command), analysis);
        }

        if (shellCommands().getReadOnly().contains(executable)) {
            return injectAnalysis(ToolPolicyDecision.readOnly("允许的只读 shell 命令", command), analysis);
        }

        if (shellCommands().getWrite().contains(executable)) {
            return injectAnalysis(ToolPolicyDecision.writeConfirm("写命令需要人工确认", command), analysis);
        }

        if (shellCommands().getHighRisk().contains(executable)) {
            return injectAnalysis(ToolPolicyDecision.highRiskConfirm("高危命令需要高危确认", command), analysis);
        }

        String unknownLevel = shellCommands().getUnknownLevel();
        if ("HIGH_RISK_DENY".equalsIgnoreCase(unknownLevel)) {
            return injectAnalysis(ToolPolicyDecision.highRiskDeny("未分类 shell 命令：" + executable, command), analysis);
        }
        if ("HIGH_RISK_CONFIRM".equalsIgnoreCase(unknownLevel)) {
            return injectAnalysis(ToolPolicyDecision.highRiskConfirm("未分类命令需要高危确认：" + executable, command), analysis);
        }
        return injectAnalysis(ToolPolicyDecision.writeConfirm("未分类命令需要人工确认：" + executable, command), analysis);
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        if (Thread.currentThread().isInterrupted()) {
            return failure("process_interrupted", "命令执行被中断", startedAt);
        }
        try {
            ToolPolicyDecision policy = policy(call);
            if (policy.getPermissionLevel() == ToolPermissionLevel.HIGH_RISK_DENY) {
                return failure("shell_command_rejected", policy.getRiskReason(), startedAt);
            }

            ShellCommandAnalysis analysis = extractAnalysis(policy);

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

            // Build execution command list based on mode
            List<String> execTokens;
            String executionModeLabel;
            if (analysis != null && analysis.getExecutionMode() == ShellExecutionMode.SHELL_EXEC) {
                String shellInterpreter = shellCommands().getShellInterpreter();
                if (shellInterpreter == null || shellInterpreter.isBlank()) {
                    shellInterpreter = "/bin/sh";
                }
                execTokens = List.of(shellInterpreter, "-c", analysis.getRawCommand());
                executionModeLabel = "SHELL_EXEC";
            } else {
                execTokens = new ArrayList<>(analysis.getTokens());
                executionModeLabel = "SIMPLE_EXEC";
            }

            ShellOutputLimits limits = ShellOutputLimits.builder()
                    .maxStdoutChars(properties.getShellMaxOutputChars())
                    .maxStderrChars(properties.getShellMaxStderrChars())
                    .build();

            if (isMavenTestCommand(execTokens)) {
                ToolResult result = commandExecutor.run(execTokens, cwd, timeoutMs, limits, startedAt);
                result.setObservation("ExecutionMode: " + executionModeLabel + "\n" +
                        StringUtils.defaultString(result.getObservation()));
                return enrichTestResult(result, execTokens, cwd, startedAt);
            }

            if (runInBackground && backgroundProcessManager != null) {
                return startBackgroundTask(execTokens, cwd, timeoutMs, call, startedAt);
            }

            if (yieldMs <= 0 || backgroundProcessManager == null) {
                ToolResult result = commandExecutor.run(execTokens, cwd, timeoutMs, limits, startedAt);
                result.setObservation("ExecutionMode: " + executionModeLabel + "\n" +
                        StringUtils.defaultString(result.getObservation()));
                return enrichTestResult(result, execTokens, cwd, startedAt);
            }

            // Try sync with yield
            if (timeoutMs > yieldMs) {
                timeoutMs = yieldMs;
            }
            ToolResult result = commandExecutor.run(execTokens, cwd, timeoutMs, limits, startedAt);

            if ("command_timeout".equals(result.getErrorCode())) {
                return startBackgroundTask(execTokens, cwd, requestedTimeoutMs, call, startedAt);
            }
            result.setObservation("ExecutionMode: " + executionModeLabel + "\n" +
                    StringUtils.defaultString(result.getObservation()));
            return enrichTestResult(result, execTokens, cwd, startedAt);
        } catch (Exception e) {
            return failure("run_shell_failed", e.getMessage(), startedAt);
        }
    }

    private ShellCommandAnalysis extractAnalysis(ToolPolicyDecision policy) {
        if (policy == null || policy.getMetadata() == null) {
            return null;
        }
        Object obj = policy.getMetadata().get("shellAnalysis");
        if (obj instanceof ShellCommandAnalysis analysis) {
            return analysis;
        }
        return null;
    }

    private ToolPolicyDecision injectAnalysis(ToolPolicyDecision decision, ShellCommandAnalysis analysis) {
        if (analysis == null || decision == null) {
            return decision;
        }
        Map<String, Object> metadata = decision.getMetadata();
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
            decision.setMetadata(metadata);
        }
        metadata.put("shellAnalysis", analysis);
        return decision;
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
        // Check raw command for SHELL_EXEC (tokens = ["/bin/sh", "-c", "mvn -q -o test"])
        if (tokens.size() >= 3 && ("/bin/sh".equals(tokens.get(0)) || "/bin/bash".equals(tokens.get(0)))
                && "-c".equals(tokens.get(1))) {
            String rawCmd = tokens.get(2);
            return rawCmd != null && rawCmd.startsWith("mvn") &&
                    (rawCmd.contains("test") || rawCmd.contains("verify") || rawCmd.contains(":test"));
        }
        // Standard tokens for SIMPLE_EXEC
        return tokens.stream().skip(1).anyMatch(token ->
                "test".equals(token)
                        || "verify".equals(token)
                        || token.endsWith(":test"));
    }

    private ToolPolicyDecision mavenPolicy(List<String> tokens, String command) {
        List<String> invalid = new ArrayList<>();
        boolean hasTestGoal = false;
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if ("test".equals(token) || "verify".equals(token)
                    || token.endsWith(":test")) {
                hasTestGoal = true;
                continue;
            }
            if (Set.of("-q", "-o", "--offline", "-B", "--batch-mode")
                    .contains(token)) {
                continue;
            }
            if (token.startsWith("-Dtest=") && token.length() > "-Dtest=".length()) {
                continue;
            }
            invalid.add(token);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reasonCode",
                hasTestGoal && invalid.isEmpty()
                        ? "maven_test_allowed" : "maven_command_not_allowed");
        metadata.put("allowedAlternatives", List.of(
                "mvn -q -o test",
                "mvn test -Dtest=TestClass"));
        if (hasTestGoal && invalid.isEmpty()) {
            return ToolPolicyDecision.builder()
                    .permissionLevel(ToolPermissionLevel.WRITE_CONFIRM)
                    .riskReason("Maven 测试会写入 target 等构建目录，需要人工确认")
                    .operationPreview(command)
                    .metadata(metadata)
                    .build();
        }
        metadata.put("rejectedArguments", invalid);
        return ToolPolicyDecision.builder()
                .permissionLevel(ToolPermissionLevel.HIGH_RISK_DENY)
                .riskReason("Maven 命令仅允许 test、verify、*:test 目标及安全测试参数；"
                        + "可改用 mvn -q -o test")
                .operationPreview(command)
                .metadata(metadata)
                .build();
    }

    private ToolResult enrichTestResult(
            ToolResult result, List<String> tokens, Path cwd, long startedAt) {
        if (!isMavenTestCommand(tokens)) {
            return result;
        }
        Map<String, Object> summary =
                SurefireTestSummary.readForExecution(cwd, startedAt);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("operationKind", "TEST");
        details.put("command", String.join(" ", tokens));
        details.put("exitCode", extractExitCode(result.getObservation()));
        details.put("passed", result.isSuccess());
        details.put("reportsAvailable", summary.get("available"));
        copySummaryCount(summary, details, "tests");
        copySummaryCount(summary, details, "failures");
        copySummaryCount(summary, details, "errors");
        copySummaryCount(summary, details, "skipped");
        result.setDetails(details);
        result.setObservation(SurefireTestSummary.render(summary)
                + "\n" + StringUtils.defaultString(result.getObservation()));
        return result;
    }

    private Integer extractExitCode(String observation) {
        if (observation == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?m)^ExitCode:\\s*(-?\\d+)\\s*$")
                .matcher(observation);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private void copySummaryCount(
            Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.get(key) instanceof Number value) {
            target.put(key, value);
        }
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

}
