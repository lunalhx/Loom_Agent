package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;

public class BackgroundProcessManager {

    private static final Logger log = LoggerFactory.getLogger(BackgroundProcessManager.class);

    private static final long MAX_OUTPUT_BYTES = 50 * 1024 * 1024; // 50 MiB

    private final Path taskLogDir;
    private final long defaultTimeoutMs;
    private final long foregroundYieldMs;
    private final long maxTimeoutMs;
    private final int globalMaxTasks;
    private final int perRunMaxTasks;
    private final BackgroundShellTaskRepository taskRepository;
    private final SandboxEnvPolicy envPolicy;

    private final Map<String, ActiveTaskHandle> activeHandles = new ConcurrentHashMap<>();
    private final Map<String, Thread> monitorThreads = new ConcurrentHashMap<>();
    private final Map<String, Set<ProcessHandle>> foregroundProcesses = new ConcurrentHashMap<>();
    private final AtomicInteger globalTaskCount = new AtomicInteger(0);
    private final ExecutorService ioExecutor;

    public BackgroundProcessManager(Path taskLogDir, long defaultTimeoutMs, long foregroundYieldMs,
                                     long maxTimeoutMs, int globalMaxTasks, int perRunMaxTasks,
                                     int ioThreads, BackgroundShellTaskRepository taskRepository,
                                     SandboxEnvPolicy envPolicy) {
        this.taskLogDir = taskLogDir;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.foregroundYieldMs = foregroundYieldMs;
        this.maxTimeoutMs = maxTimeoutMs;
        this.globalMaxTasks = globalMaxTasks;
        this.perRunMaxTasks = perRunMaxTasks;
        this.taskRepository = taskRepository;
        this.envPolicy = envPolicy;
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
        return (int) activeHandles.keySet().stream()
                .filter(k -> k.startsWith(runId + "/"))
                .count();
    }

    public record SyncResult(boolean success, String errorCode, String message,
                              String observation, boolean truncated, long elapsedMs) {}

    public SyncResult runSync(List<String> command, Path cwd, Map<String, String> extraEnv,
                              long timeoutMs, ShellOutputLimits limits, long startedAt) {
        return runSync(command, cwd, extraEnv, timeoutMs, limits, startedAt, null);
    }

    public SyncResult runSync(List<String> command, Path cwd, Map<String, String> extraEnv,
                              long timeoutMs, ShellOutputLimits limits, long startedAt,
                              String conversationId) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
            Map<String, String> originalEnv = Map.copyOf(builder.environment());
            builder.environment().clear();
            builder.environment().putAll(envPolicy.filter(originalEnv, extraEnv));

            process = builder.start();
            if (conversationId != null) {
                foregroundProcesses.computeIfAbsent(conversationId, ignored -> ConcurrentHashMap.newKeySet())
                        .add(process.toHandle());
            }
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
                ProcessGroupRunner.terminate(process.toHandle(), 5000);
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
                ProcessGroupRunner.terminate(process.toHandle(), 5000);
            }
            return new SyncResult(false, "process_interrupted", "命令执行被中断", null, false,
                    System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            return new SyncResult(false, "process_failed", e.getMessage(), null, false,
                    System.currentTimeMillis() - startedAt);
        } finally {
            if (process != null && conversationId != null) {
                Set<ProcessHandle> handles = foregroundProcesses.get(conversationId);
                if (handles != null) {
                    handles.remove(process.toHandle());
                    if (handles.isEmpty()) {
                        foregroundProcesses.remove(conversationId, handles);
                    }
                }
            }
        }
    }

    public record BackgroundStartResult(boolean started, String errorCode, String message,
                                         BackgroundShellTask task) {}

    public BackgroundStartResult startBackground(List<String> command, Path cwd, long requestedTimeoutMs,
                                                  String runId, String conversationId, String workspace,
                                                  BackgroundLaunchMode launchMode) {
        return startBackground(command, cwd, Map.of(), requestedTimeoutMs, runId,
                conversationId, workspace, launchMode);
    }

    public BackgroundStartResult startBackground(List<String> command, Path cwd,
                                                  Map<String, String> extraEnv, long requestedTimeoutMs,
                                                  String runId, String conversationId, String workspace,
                                                  BackgroundLaunchMode launchMode) {
        return startBackground(command, cwd, extraEnv, requestedTimeoutMs, runId,
                conversationId, workspace, launchMode, taskLogDir);
    }

    public BackgroundStartResult startBackground(List<String> command, Path cwd,
                                                  Map<String, String> extraEnv, long requestedTimeoutMs,
                                                  String runId, String conversationId, String workspace,
                                                  BackgroundLaunchMode launchMode, Path taskStateRoot) {
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
        Path logDir;
        try {
            logDir = safeTaskLogDir(taskStateRoot, runId, taskId);
        } catch (Exception e) {
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
                .envKeys(envPolicy.extraEnvKeys(extraEnv))
                .startedAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
            Map<String, String> originalEnv = Map.copyOf(builder.environment());
            builder.environment().clear();
            builder.environment().putAll(envPolicy.filter(originalEnv, extraEnv));

            Process process = builder.start();
            task.setPid(process.pid());
            task.setStatus(BackgroundTaskStatus.RUNNING);
            globalTaskCount.incrementAndGet();

            String registryKey = runId + "/" + taskId;
            ActiveTaskHandle handle = new ActiveTaskHandle(process, task, registryKey);
            activeHandles.put(registryKey, handle);

            if (taskRepository != null) {
                taskRepository.save(task);
            }

            ioExecutor.submit(() -> streamToFile(process.getInputStream(), stdoutFile, handle));
            ioExecutor.submit(() -> streamToFile(process.getErrorStream(), stderrFile, handle));

            Thread monitor = new Thread(() -> monitorProcess(handle, actualTimeout), "bg-monitor-" + taskId);
            monitor.setDaemon(true);
            monitor.start();
            monitorThreads.put(registryKey, monitor);

            return new BackgroundStartResult(true, null, null, task);
        } catch (Exception e) {
            task.setStatus(BackgroundTaskStatus.FAILED);
            task.setErrorCode("background_start_failed");
            task.setErrorMessage(e.getMessage());
            task.setCompletedAt(Instant.now());
            task.setUpdatedAt(Instant.now());
            if (taskRepository != null) {
                taskRepository.save(task);
            }
            return new BackgroundStartResult(false, "background_start_failed",
                    "后台进程启动失败: " + e.getMessage(), task);
        }
    }

    private Path safeTaskLogDir(Path root, String runId, String taskId) throws IOException {
        if (runId == null || !runId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("Invalid run id");
        }
        Files.createDirectories(root);
        Path realRoot = root.toRealPath();
        Path resolved = realRoot.resolve(runId).resolve(taskId).normalize();
        if (!resolved.startsWith(realRoot)) {
            throw new IllegalArgumentException("Background task path escapes its session root");
        }
        Path ancestor = resolved;
        while (ancestor != null && !Files.exists(ancestor, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null || !ancestor.toRealPath().startsWith(realRoot)) {
            throw new IOException("Background task path escapes through a symbolic link");
        }
        Files.createDirectories(resolved);
        Path realResolved = resolved.toRealPath();
        if (!realResolved.startsWith(realRoot)) {
            throw new IOException("Background task path escapes through a symbolic link");
        }
        return realResolved;
    }

    private void streamToFile(InputStream in, Path file, ActiveTaskHandle handle) {
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
                handle.task.setStdoutBytes(total);
            } else {
                handle.task.setStderrBytes(total);
            }
            if (total >= MAX_OUTPUT_BYTES && !handle.cancelled.get()) {
                killTask(handle, "output_limit_exceeded", "输出超过 50 MiB 上限");
            }
            while (in.read(buf) >= 0) {
                // drain
            }
        } catch (Exception ignored) {
            // stream closed
        }
    }

    private void monitorProcess(ActiveTaskHandle handle, long timeoutMs) {
        try {
            boolean completed = handle.process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (handle.cancelled.get()) {
                return;
            }
            if (!completed) {
                ProcessGroupRunner.terminate(handle.process.toHandle(), 5000);
                handle.task.setStatus(BackgroundTaskStatus.TIMED_OUT);
                handle.task.setErrorCode("command_timeout");
                handle.task.setErrorMessage("命令执行超时");
            } else {
                int exitCode = handle.process.exitValue();
                handle.task.setExitCode(exitCode);
                if (exitCode == 0) {
                    handle.task.setStatus(BackgroundTaskStatus.SUCCEEDED);
                } else {
                    handle.task.setStatus(BackgroundTaskStatus.FAILED);
                    handle.task.setErrorCode("command_failed");
                    handle.task.setErrorMessage("命令退出码：" + exitCode);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (handle.cancelled.get()) {
                return;
            }
            handle.task.setStatus(BackgroundTaskStatus.FAILED);
            handle.task.setErrorCode("monitor_interrupted");
            handle.task.setErrorMessage("监控线程被中断");
        } finally {
            cleanup(handle);
        }
    }

    public boolean cancelProcess(String runId, String taskId) {
        String key = runId + "/" + taskId;
        ActiveTaskHandle handle = activeHandles.get(key);
        if (handle == null) {
            return false;
        }
        if (!handle.cancelled.compareAndSet(false, true)) {
            return true;
        }
        handle.task.setStatus(BackgroundTaskStatus.CANCELLED);
        handle.task.setCompletedAt(Instant.now());
        handle.task.setUpdatedAt(Instant.now());
        ProcessGroupRunner.terminate(handle.process.toHandle(), 5000);
        cleanup(handle);
        return true;
    }

    public void cancelAllProcessesForRun(String runId) {
        activeHandles.keySet().stream()
                .filter(k -> k.startsWith(runId + "/"))
                .toList()
                .forEach(k -> {
                    String taskId = k.substring(runId.length() + 1);
                    cancelProcess(runId, taskId);
                });
    }

    public int activeProcessCountForConversation(String conversationId) {
        int foreground = foregroundProcesses.getOrDefault(conversationId, Set.of()).size();
        int background = (int) activeHandles.values().stream()
                .filter(handle -> conversationId.equals(handle.task.getConversationId()))
                .count();
        return foreground + background;
    }

    public void cancelAllProcessesForConversation(String conversationId) {
        for (ProcessHandle handle : List.copyOf(foregroundProcesses.getOrDefault(conversationId, Set.of()))) {
            ProcessGroupRunner.terminate(handle, 5000);
        }
        foregroundProcesses.remove(conversationId);
        activeHandles.values().stream()
                .filter(handle -> conversationId.equals(handle.task.getConversationId()))
                .map(handle -> handle.registryKey)
                .toList()
                .forEach(key -> {
                    int separator = key.indexOf('/');
                    cancelProcess(key.substring(0, separator), key.substring(separator + 1));
                });
    }

    public void shutdown() {
        for (ActiveTaskHandle h : List.copyOf(activeHandles.values())) {
            h.cancelled.set(true);
            ProcessGroupRunner.terminate(h.process.toHandle(), 5000);
        }
        activeHandles.clear();
        monitorThreads.values().forEach(Thread::interrupt);
        monitorThreads.clear();
        foregroundProcesses.values().forEach(handles -> handles.forEach(
                handle -> ProcessGroupRunner.terminate(handle, 5000)));
        foregroundProcesses.clear();
        ioExecutor.shutdownNow();
    }

    private void killTask(ActiveTaskHandle handle, String errorCode, String message) {
        if (!handle.cancelled.compareAndSet(false, true)) {
            return;
        }
        ProcessGroupRunner.terminate(handle.process.toHandle(), 5000);
        handle.task.setStatus(BackgroundTaskStatus.FAILED);
        handle.task.setErrorCode(errorCode);
        handle.task.setErrorMessage(message);
        handle.task.setCompletedAt(Instant.now());
        handle.task.setUpdatedAt(Instant.now());
        cleanup(handle);
    }

    private void cleanup(ActiveTaskHandle handle) {
        if (!handle.cleanedUp.compareAndSet(false, true)) {
            return;
        }
        if (handle.task.getCompletedAt() == null) {
            handle.task.setCompletedAt(Instant.now());
        }
        if (handle.task.getUpdatedAt() == null) {
            handle.task.setUpdatedAt(Instant.now());
        }
        activeHandles.remove(handle.registryKey);
        monitorThreads.remove(handle.registryKey);
        globalTaskCount.decrementAndGet();
        if (taskRepository != null) {
            taskRepository.save(handle.task);
        }
    }

    private static final class ActiveTaskHandle {
        final Process process;
        final BackgroundShellTask task;
        final String registryKey;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean cleanedUp = new AtomicBoolean(false);

        ActiveTaskHandle(Process process, BackgroundShellTask task, String registryKey) {
            this.process = process;
            this.task = task;
            this.registryKey = registryKey;
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
