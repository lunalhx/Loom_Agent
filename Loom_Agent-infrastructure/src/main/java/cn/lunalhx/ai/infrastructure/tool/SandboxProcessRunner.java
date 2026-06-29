package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.tool.model.ShellOutputLimits;
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

    static ToolResult run(List<String> command, Path cwd, long timeoutMs, ShellOutputLimits limits, long startedAt) {
        Process process = null;
        StreamReader stdoutReader = null;
        StreamReader stderrReader = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(cwd.toFile());
            Map<String, String> originalEnv = Map.copyOf(builder.environment());
            builder.environment().clear();
            ENV_ALLOW_LIST.forEach(key -> {
                if (originalEnv.containsKey(key)) {
                    builder.environment().put(key, originalEnv.get(key));
                }
            });

            process = builder.start();
            stdoutReader = new StreamReader(process.getInputStream(), limits.getMaxStdoutChars());
            stderrReader = new StreamReader(process.getErrorStream(), limits.getMaxStderrChars());
            Thread stdoutThread = new Thread(stdoutReader, "agent-tool-process-stdout");
            stdoutThread.setDaemon(true);
            stdoutThread.start();
            Thread stderrThread = new Thread(stderrReader, "agent-tool-process-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            boolean completed = process.waitFor(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                stdoutThread.join(1000L);
                stderrThread.join(1000L);
                return ToolResult.builder()
                        .success(false)
                        .errorCode("command_timeout")
                        .message("命令执行超时")
                        .observation(observation(command, cwd, null, stdoutReader, stderrReader))
                        .truncated(stdoutReader.isTruncated() || stderrReader.isTruncated())
                        .elapsedMs(elapsed(startedAt))
                        .build();
            }
            stdoutThread.join(1000L);
            stderrThread.join(1000L);
            int exitCode = process.exitValue();
            boolean truncated = stdoutReader.isTruncated() || stderrReader.isTruncated();
            return ToolResult.builder()
                    .success(exitCode == 0)
                    .errorCode(exitCode == 0 ? null : "command_failed")
                    .message(exitCode == 0 ? null : "命令退出码：" + exitCode)
                    .observation(observation(command, cwd, exitCode, stdoutReader, stderrReader))
                    .truncated(truncated)
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

    private static String observation(List<String> command, Path cwd, Integer exitCode,
                                       StreamReader stdoutReader, StreamReader stderrReader) {
        StringBuilder text = new StringBuilder();
        text.append("Command: ").append(String.join(" ", command)).append('\n');
        text.append("Cwd: ").append(cwd).append('\n');
        if (exitCode != null) {
            text.append("ExitCode: ").append(exitCode).append('\n');
        }
        text.append("[stdout]:\n").append(stdoutReader.getOutput());
        text.append("\n[stderr]:\n").append(stderrReader.getOutput());
        if (stdoutReader.isTruncated() || stderrReader.isTruncated()) {
            text.append("\n[truncated");
            if (stdoutReader.isTruncated()) {
                text.append(": stdout");
            }
            if (stderrReader.isTruncated()) {
                text.append(stdoutReader.isTruncated() ? ", stderr" : ": stderr");
            }
            text.append("]");
        }
        return text.toString();
    }

    private static long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

    private static final class StreamReader implements Runnable {

        private final InputStream in;
        private final int maxChars;
        private final StringBuilder output = new StringBuilder();
        private boolean truncated;

        StreamReader(InputStream in, int maxChars) {
            this.in = in;
            this.maxChars = maxChars;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[4096];
            try (InputStream input = in) {
                int length;
                while ((length = input.read(buffer)) >= 0) {
                    if (output.length() < maxChars) {
                        String chunk = new String(buffer, 0, length, StandardCharsets.UTF_8);
                        int remaining = maxChars - output.length();
                        if (chunk.length() > remaining) {
                            output.append(chunk, 0, remaining);
                            truncated = true;
                        } else {
                            output.append(chunk);
                        }
                    } else {
                        truncated = true;
                    }
                }
            } catch (Exception ignored) {
                truncated = true;
            }
        }

        String getOutput() {
            return output.toString();
        }

        boolean isTruncated() {
            return truncated;
        }
    }

}
