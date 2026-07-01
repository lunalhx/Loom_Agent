package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.tool.model.BackgroundLaunchMode;
import cn.lunalhx.ai.domain.tool.model.BackgroundShellTask;
import cn.lunalhx.ai.domain.tool.model.BackgroundTaskStatus;
import cn.lunalhx.ai.domain.tool.model.ShellOutputLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BackgroundProcessManager {

    private static final Logger log = LoggerFactory.getLogger(BackgroundProcessManager.class);

    private static final Set<String> ENV_ALLOW_LIST = Set.of("PATH", "JAVA_HOME", "M2_HOME", "MAVEN_OPTS", "HOME");
    private static final long MAX_OUTPUT_BYTES = 50 * 1024 * 1024; // 50 MiB

    private final Path taskLogDir;
    private final long defaultTimeoutMs;
    private final long foregroundYieldMs;
    private final long maxTimeoutMs;
    private final int globalMaxTasks;
    private final int perRunMaxTasks;

    private final Map<String, Process> processRegistry = new ConcurrentHashMap<>();
    private final Map<String, Thread> monitorThreads = new ConcurrentHashMap<>();
    private final AtomicInteger globalTaskCount = new AtomicInteger(0);
    private final ExecutorService ioExecutor;

    public BackgroundProcessManager(Path taskLogDir, long defaultTimeoutMs, long foregroundYieldMs,
                                     long maxTimeoutMs, int globalMaxTasks, int perRunMaxTasks,
                                     int ioThreads) {
        this.taskLogDir = taskLogDir;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.foregroundYieldMs = foregroundYieldMs;
        this.maxTimeoutMs = maxTimeoutMs;
        this.globalMaxTasks = globalMaxTasks;
        this.perRunMaxTasks = perRunMaxTasks;
        this.ioExecutor = Executors.newFixedThreadPool(ioThreads, r -> {
            Thread t = new Thread(r, "bg-task-io");
            t.setDaemon(true);
            return t;
        });
    }

    public int globalActiveCount() {
        return globalTaskCount.get();
    }

    public int perRunActiveCount(String runId) {
        return (int) processRegistry.keySet().stream()
                .filter(k -> k.startsWith(runId + "/"))
                .count();
    }

    public record SyncResult(boolean success, String errorCode, String message,
                              String observation, boolean truncated, long elapsedMs) {}

    public SyncResult runSync(List<String> command, Path cwd, long timeoutMs, ShellOutputLimits limits, long startedAt) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
            Map<String, String> originalEnv = Map.copyOf(builder.environment());
            builder.environment().clear();
            ENV_ALLOW_LIST.forEach(key -> {
                if (originalEnv.containsKey(key)) {
                    builder.environment().put(key, originalEnv.get(key));
                }
            });

            process = builder.start();
            StreamCollector stdout = new StreamCollector(process.getInputStream(), limits.getMaxStdoutChars());
            StreamCollector stderr = new StreamCollector(process.getErrorStream(), limits.getMaxStderrChars());
            Thread stdoutThread = new Thread(stdout, "sync-stdout");
            stdoutThread.setDaemon(true);
            stdoutThread.start();
            Thread stderrThread = new Thread(stderr, "sync-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            boolean completed = process.waitFor(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                stdoutThread.join(1000L);
                stderrThread.join(1000L);
                return new SyncResult(false, "command_timeout", "命令执行超时",
                        observation(command, cwd, null, stdout, stderr),
                        stdout.isTruncated() || stderr.isTruncated(),
                        System.currentTimeMillis() - startedAt);
            }
            stdoutThread.join(1000L);
            stderrThread.join(1000L);
            int exitCode = process.exitValue();
            return new SyncResult(exitCode == 0,
                    exitCode == 0 ? null : "command_failed",
                    exitCode == 0 ? null : "命令退出码：" + exitCode,
                    observation(command, cwd, exitCode, stdout, stderr),
                    stdout.isTruncated() || stderr.isTruncated(),
                    System.currentTimeMillis() - startedAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return new SyncResult(false, "process_interrupted", "命令执行被中断", null, false,
                    System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            return new SyncResult(false, "process_failed", e.getMessage(), null, false,
                    System.currentTimeMillis() - startedAt);
        }
    }

    public record BackgroundStartResult(boolean started, String errorCode, String message,
                                         BackgroundShellTask task) {}

    public BackgroundStartResult startBackground(List<String> command, Path cwd, long requestedTimeoutMs,
                                                   String runId, String conversationId, String workspace,
                                                   BackgroundLaunchMode launchMode) {
        long timeoutMs = Math.min(Math.max(1L, requestedTimeoutMs), maxTimeoutMs);
        long actualTimeout = timeoutMs > 0 ? timeoutMs : defaultTimeoutMs;

        if (globalTaskCount.get() >= globalMaxTasks) {
            return new BackgroundStartResult(false, "background_task_limit",
                    "全局后台任务已达上限 " + globalMaxTasks, null);
        }
        long perRunCount = perRunActiveCount(runId);
        if (perRunCount >= perRunMaxTasks) {
            return new BackgroundStartResult(false, "background_task_limit",
                    "当前 run 后台任务已达上限 " + perRunMaxTasks, null);
        }

        String taskId = UUID.randomUUID().toString();
        Path logDir = taskLogDir.resolve(runId).resolve(taskId);
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            return new BackgroundStartResult(false, "background_task_io_error",
                    "无法创建任务日志目录: " + e.getMessage(), null);
        }
        Path stdoutFile = logDir.resolve("stdout.log");
        Path stderrFile = logDir.resolve("stderr.log");

        BackgroundShellTask task = BackgroundShellTask.builder()
                .taskId(taskId)
                .runId(runId)
                .conversationId(conversationId)
                .workspace(workspace)
                .command(String.join(" ", command))
                .cwd(cwd.toString())
                .launchMode(launchMode)
                .timeoutMs(actualTimeout)
                .status(BackgroundTaskStatus.STARTING)
                .stdoutFile(stdoutFile.toString())
                .stderrFile(stderrFile.toString())
                .startedAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
            Map<String, String> originalEnv = Map.copyOf(builder.environment());
            builder.environment().clear();
            ENV_ALLOW_LIST.forEach(key -> {
                if (originalEnv.containsKey(key)) {
                    builder.environment().put(key, originalEnv.get(key));
                }
            });

            Process process = builder.start();
            task.setPid(process.pid());
            task.setStatus(BackgroundTaskStatus.RUNNING);
            globalTaskCount.incrementAndGet();

            String registryKey = runId + "/" + taskId;
            processRegistry.put(registryKey, process);

            ioExecutor.submit(() -> streamToFile(process.getInputStream(), stdoutFile, task));
            ioExecutor.submit(() -> streamToFile(process.getErrorStream(), stderrFile, task));

            Thread monitor = new Thread(() -> monitorProcess(process, task, actualTimeout), "bg-monitor-" + taskId);
            monitor.setDaemon(true);
            monitor.start();
            monitorThreads.put(registryKey, monitor);

            return new BackgroundStartResult(true, null, null, task);
        } catch (Exception e) {
            task.setStatus(BackgroundTaskStatus.FAILED);
            task.setErrorCode("background_start_failed");
            task.setErrorMessage(e.getMessage());
            task.setCompletedAt(Instant.now());
            return new BackgroundStartResult(false, "background_start_failed",
                    "后台进程启动失败: " + e.getMessage(), task);
        }
    }

    private void streamToFile(InputStream in, Path file, BackgroundShellTask task) {
        try (OutputStream out = Files.newOutputStream(file)) {
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (total + n > MAX_OUTPUT_BYTES) {
                    out.write(buf, 0, (int) (MAX_OUTPUT_BYTES - total));
                    total = MAX_OUTPUT_BYTES;
                    break;
                }
                out.write(buf, 0, n);
                total += n;
            }
            if (file.toString().endsWith("stdout.log")) {
                task.setStdoutBytes(total);
            } else {
                task.setStderrBytes(total);
            }
            if (total >= MAX_OUTPUT_BYTES) {
                killTask(task, "output_limit_exceeded", "输出超过 50 MiB 上限");
            }
            while (in.read(buf) >= 0) {
                // drain
            }
        } catch (Exception ignored) {
            // stream closed
        }
    }

    private void monitorProcess(Process process, BackgroundShellTask task, long timeoutMs) {
        try {
            boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                killProcessTree(process);
                task.setStatus(BackgroundTaskStatus.TIMED_OUT);
                task.setErrorCode("command_timeout");
                task.setErrorMessage("命令执行超时");
            } else {
                int exitCode = process.exitValue();
                task.setExitCode(exitCode);
                if (exitCode == 0) {
                    task.setStatus(BackgroundTaskStatus.SUCCEEDED);
                } else {
                    task.setStatus(BackgroundTaskStatus.FAILED);
                    task.setErrorCode("command_failed");
                    task.setErrorMessage("命令退出码：" + exitCode);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.setStatus(BackgroundTaskStatus.FAILED);
            task.setErrorCode("monitor_interrupted");
            task.setErrorMessage("监控线程被中断");
        }
        task.setCompletedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        String registryKey = task.getRunId() + "/" + task.getTaskId();
        processRegistry.remove(registryKey);
        monitorThreads.remove(registryKey);
        globalTaskCount.decrementAndGet();
    }

    public boolean cancel(String runId, String taskId) {
        String key = runId + "/" + taskId;
        Process process = processRegistry.get(key);
        if (process == null) {
            return false;
        }
        killProcessTree(process);
        processRegistry.remove(key);
        Thread monitor = monitorThreads.remove(key);
        if (monitor != null) {
            monitor.interrupt();
        }
        globalTaskCount.decrementAndGet();
        return true;
    }

    public void cancelAllForRun(String runId) {
        processRegistry.keySet().stream()
                .filter(k -> k.startsWith(runId + "/"))
                .toList()
                .forEach(k -> {
                    String taskId = k.substring(runId.length() + 1);
                    cancel(runId, taskId);
                });
    }

    public void shutdown() {
        processRegistry.values().forEach(this::killProcessTree);
        processRegistry.clear();
        monitorThreads.values().forEach(Thread::interrupt);
        monitorThreads.clear();
        ioExecutor.shutdownNow();
    }

    private void killTask(BackgroundShellTask task, String errorCode, String message) {
        String key = task.getRunId() + "/" + task.getTaskId();
        Process process = processRegistry.get(key);
        if (process != null) {
            killProcessTree(process);
            processRegistry.remove(key);
        }
        task.setStatus(BackgroundTaskStatus.FAILED);
        task.setErrorCode(errorCode);
        task.setErrorMessage(message);
        task.setCompletedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        globalTaskCount.decrementAndGet();
    }

    private void killProcessTree(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            ProcessHandle handle = process.toHandle();
            handle.descendants().forEach(ph -> {
                try {
                    ph.destroyForcibly();
                } catch (Exception ignored) {
                }
            });
            handle.destroyForcibly();
            handle.descendants().forEach(ph -> {
                try {
                    ph.destroyForcibly();
                } catch (Exception ignored) {
                }
            });
        } catch (Exception e) {
            process.destroyForcibly();
        }
    }

    private static String observation(List<String> command, Path cwd, Integer exitCode,
                                       StreamCollector stdout, StreamCollector stderr) {
        StringBuilder text = new StringBuilder();
        text.append("Command: ").append(String.join(" ", command)).append('\n');
        text.append("Cwd: ").append(cwd).append('\n');
        if (exitCode != null) {
            text.append("ExitCode: ").append(exitCode).append('\n');
        }
        text.append("[stdout]:\n").append(stdout.getOutput());
        text.append("\n[stderr]:\n").append(stderr.getOutput());
        if (stdout.isTruncated() || stderr.isTruncated()) {
            text.append("\n[truncated");
            if (stdout.isTruncated()) {
                text.append(": stdout");
            }
            if (stderr.isTruncated()) {
                text.append(stdout.isTruncated() ? ", stderr" : ": stderr");
            }
            text.append("]");
        }
        return text.toString();
    }

    private static final class StreamCollector implements Runnable {

        private final InputStream in;
        private final int maxChars;
        private final StringBuilder output = new StringBuilder();
        private boolean truncated;

        StreamCollector(InputStream in, int maxChars) {
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
