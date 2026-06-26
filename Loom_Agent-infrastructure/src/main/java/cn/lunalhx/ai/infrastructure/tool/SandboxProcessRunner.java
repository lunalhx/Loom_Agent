package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.tool.model.ToolResult;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class SandboxProcessRunner {

    private static final Set<String> ENV_ALLOW_LIST = Set.of("PATH", "JAVA_HOME", "M2_HOME", "MAVEN_OPTS", "HOME");

    private SandboxProcessRunner() {
    }

    static ToolResult run(List<String> command, Path cwd, long timeoutMs, int maxOutputChars, long startedAt) {
        StringBuilder output = new StringBuilder();
        boolean[] truncated = new boolean[]{false};
        Process process = null;
        Thread reader = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true);
            Map<String, String> originalEnv = Map.copyOf(builder.environment());
            builder.environment().clear();
            ENV_ALLOW_LIST.forEach(key -> {
                if (originalEnv.containsKey(key)) {
                    builder.environment().put(key, originalEnv.get(key));
                }
            });

            process = builder.start();
            InputStream processInput = process.getInputStream();
            reader = new Thread(() -> readOutput(processInput, output, maxOutputChars, truncated), "agent-tool-process-output");
            reader.setDaemon(true);
            reader.start();

            boolean completed = process.waitFor(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                reader.join(1000L);
                return ToolResult.builder()
                        .success(false)
                        .errorCode("command_timeout")
                        .message("命令执行超时")
                        .observation(observation(command, cwd, null, output.toString(), true))
                        .truncated(true)
                        .elapsedMs(elapsed(startedAt))
                        .build();
            }
            reader.join(1000L);
            int exitCode = process.exitValue();
            return ToolResult.builder()
                    .success(exitCode == 0)
                    .errorCode(exitCode == 0 ? null : "command_failed")
                    .message(exitCode == 0 ? null : "命令退出码：" + exitCode)
                    .observation(observation(command, cwd, exitCode, output.toString(), truncated[0]))
                    .truncated(truncated[0])
                    .elapsedMs(elapsed(startedAt))
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return ToolResult.failure("process_interrupted", "命令执行被中断", elapsed(startedAt));
        } catch (Exception e) {
            return ToolResult.failure("process_failed", e.getMessage(), elapsed(startedAt));
        }
    }

    private static void readOutput(InputStream inputStream, StringBuilder output, int maxOutputChars, boolean[] truncated) {
        byte[] buffer = new byte[4096];
        try (InputStream in = inputStream) {
            int length;
            while ((length = in.read(buffer)) >= 0) {
                if (output.length() < maxOutputChars) {
                    String chunk = new String(buffer, 0, length, StandardCharsets.UTF_8);
                    int remaining = maxOutputChars - output.length();
                    if (chunk.length() > remaining) {
                        output.append(chunk, 0, remaining);
                        truncated[0] = true;
                    } else {
                        output.append(chunk);
                    }
                } else {
                    truncated[0] = true;
                }
            }
        } catch (Exception ignored) {
            truncated[0] = true;
        }
    }

    private static String observation(List<String> command, Path cwd, Integer exitCode, String output, boolean truncated) {
        StringBuilder text = new StringBuilder();
        text.append("Command: ").append(String.join(" ", command)).append('\n');
        text.append("Cwd: ").append(cwd).append('\n');
        if (exitCode != null) {
            text.append("ExitCode: ").append(exitCode).append('\n');
        }
        text.append("Output:\n").append(output == null ? "" : output);
        if (truncated) {
            text.append("\n[truncated]");
        }
        return text.toString();
    }

    private static long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

}
