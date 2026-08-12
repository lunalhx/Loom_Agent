package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.tool.model.ShellExecutionResult;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfileKind;
import cn.lunalhx.ai.domain.agent.model.entity.RootRunSecurityScope;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * loom-code run_shell execution: run /bin/sh -c in the repo root with a
 * filtered environment and force PWD to the workspace root.
 */
public final class ShellRunner {

    private static final int OUTPUT_LIMIT = 1024 * 1024;
    private static final int OUTPUT_EDGE = OUTPUT_LIMIT / 2;

    private static final Set<String> ALLOWED_ENV_KEYS = Set.of(
            "HOME", "LANG", "LC_ALL", "LC_CTYPE", "LOGNAME", "PATH", "PWD",
            "SHELL", "TERM", "TMPDIR", "TMP", "TEMP", "USER");

    private static final Pattern SENSITIVE_SUFFIX = Pattern.compile("(API_KEY|TOKEN|SECRET|PASSWORD)$");

    public record ShellResult(String stdout, String stderr, ShellExecutionResult execution) {
    }

    private ShellRunner() {
    }

    public static ShellResult run(String command, Path cwd, int timeoutSeconds, Set<String> secretEnvNames) {
        return run(command, cwd, timeoutSeconds, secretEnvNames, null);
    }

    /** Executes the command through the selected native sandbox when a profile is supplied. */
    public static ShellResult run(String command, Path cwd, int timeoutSeconds, Set<String> secretEnvNames,
                                  ExecutionProfile profile) {
        return run(command, cwd, timeoutSeconds, secretEnvNames, profile, null);
    }

    public static ShellResult run(String command, Path cwd, int timeoutSeconds, Set<String> secretEnvNames,
                                  ExecutionProfile profile, RootRunSecurityScope scope) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : ALLOWED_ENV_KEYS) {
            String value = System.getenv(key);
            if (value != null) {
                env.put(key, value);
            }
        }
        if (profile != null && profile.kind() != ExecutionProfileKind.DANGER_FULL_ACCESS) {
            if (profile.homeRoot() == null || profile.temporaryRoot() == null) {
                return new ShellResult("", "error: sandbox disposable roots are unavailable", new ShellExecutionResult(-1,
                        ShellExecutionResult.TerminationReason.LAUNCH_FAILED, false, false, false));
            }
            env.put("HOME", profile.homeRoot().toString());
            env.put("TMPDIR", profile.temporaryRoot().toString());
            env.put("TMP", profile.temporaryRoot().toString());
            env.put("TEMP", profile.temporaryRoot().toString());
        }

        List<String> shell = List.of("/bin/sh", "-c", command);
        List<String> target = profile == null ? shell : NativeSandboxBackend.wrap(profile, shell);
        List<String> argv = profile == null ? target : NativeLauncher.wrap(target);
        ProcessBuilder builder = new ProcessBuilder(argv)
                .directory(cwd.toFile());
        Map<String, String> builderEnv = builder.environment();
        builderEnv.clear();
        for (Map.Entry<String, String> e : env.entrySet()) {
            String key = e.getKey();
            if (isSensitive(key, secretEnvNames)) {
                continue;
            }
            builderEnv.put(key, e.getValue());
        }
        builderEnv.put("PWD", cwd.toString());

        RootRunSecurityScope.ShellLease lease = null;
        Runnable canceller = null;
        try {
            if (scope != null) lease = scope.acquireShell();
            Process process = builder.start();
            PosixProcessSupervisor supervisor = new PosixProcessSupervisor();
            canceller = () -> supervisor.terminate(process);
            if (scope != null) scope.registerShellCanceller(canceller);
            if (profile != null && !NativeLauncher.awaitReadyAndRelease(process, supervisor)) {
                supervisor.terminate(process);
                return new ShellResult("", "error: native launcher initialization failed", new ShellExecutionResult(-1,
                        ShellExecutionResult.TerminationReason.LAUNCH_FAILED, false, false, false));
            }
            List<Thread> readers = new ArrayList<>();
            BoundedOutput stdout = new BoundedOutput();
            BoundedOutput stderr = new BoundedOutput();
            readers.add(startReader(process.getInputStream(), stdout));
            readers.add(startReader(process.getErrorStream(), stderr));
            boolean completed = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            boolean cleanedBackground = false;
            if (!completed) {
                if (profile != null) supervisor.terminate(process);
                else {
                    terminateTree(process);
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } else if (profile != null) {
                cleanedBackground = supervisor.terminateRemainingGroup(process);
            } else {
                cleanedBackground = terminateLiveDescendants(process);
            }
            for (Thread t : readers) {
                t.join(1000);
            }
            int code = completed && !process.isAlive() ? process.exitValue() : -1;
            return new ShellResult(stdout.value(), stderr.value(), new ShellExecutionResult(code,
                    scope != null && scope.isCancelled() ? ShellExecutionResult.TerminationReason.CANCELLED
                            : completed ? ShellExecutionResult.TerminationReason.EXITED
                            : ShellExecutionResult.TerminationReason.TIMED_OUT,
                    stdout.truncated(), stderr.truncated(), cleanedBackground));
        } catch (IOException e) {
            return new ShellResult("", "error: " + e.getMessage(), new ShellExecutionResult(-1,
                    ShellExecutionResult.TerminationReason.LAUNCH_FAILED, false, false, false));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ShellResult("", "error: interrupted", new ShellExecutionResult(-1,
                    ShellExecutionResult.TerminationReason.INTERRUPTED, false, false, false));
        } finally {
            if (scope != null && canceller != null) scope.unregisterShellCanceller(canceller);
            if (lease != null) lease.close();
        }
    }

    private static boolean isSensitive(String key, Set<String> extra) {
        String upper = key.toUpperCase();
        if (extra != null && extra.contains(upper)) {
            return true;
        }
        return SENSITIVE_SUFFIX.matcher(upper).find();
    }

    private static void terminateTree(Process process) {
        terminateLiveDescendants(process);
        process.destroy();
        try { process.waitFor(500, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (process.isAlive()) process.destroyForcibly();
    }

    private static boolean terminateLiveDescendants(Process process) {
        List<ProcessHandle> descendants;
        try {
            descendants = process.toHandle().descendants().toList();
        } catch (RuntimeException unavailable) {
            // Some host sandboxes deny process enumeration.  The shell result remains
            // truthful; the native supervisor added by the sandbox layer owns cleanup.
            return false;
        }
        boolean found = descendants.stream().anyMatch(ProcessHandle::isAlive);
        descendants.forEach(handle -> { if (handle.isAlive()) handle.destroy(); });
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        descendants.forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
        return found;
    }

    private static Thread startReader(InputStream in, BoundedOutput out) {
        Thread t = new Thread(() -> {
            try (InputStream is = in) {
                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) >= 0) {
                    out.append(buffer, len);
                }
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static final class BoundedOutput {
        private final java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream(OUTPUT_EDGE);
        private final java.io.ByteArrayOutputStream complete = new java.io.ByteArrayOutputStream(OUTPUT_LIMIT);
        private final byte[] tail = new byte[OUTPUT_EDGE];
        private long total;

        synchronized void append(byte[] bytes, int length) {
            for (int i = 0; i < length; i++) {
                if (total < OUTPUT_EDGE) head.write(bytes[i]);
                if (total < OUTPUT_LIMIT) complete.write(bytes[i]);
                else tail[(int) (total % OUTPUT_EDGE)] = bytes[i];
                total++;
            }
        }

        synchronized String value() {
            if (!truncated()) return complete.toString(StandardCharsets.UTF_8);
            byte[] orderedTail = new byte[OUTPUT_EDGE];
            int start = (int) (total % OUTPUT_EDGE);
            System.arraycopy(tail, start, orderedTail, 0, OUTPUT_EDGE - start);
            System.arraycopy(tail, 0, orderedTail, OUTPUT_EDGE - start, start);
            return head.toString(StandardCharsets.UTF_8) + "\n[output truncated]\n"
                    + new String(orderedTail, StandardCharsets.UTF_8);
        }

        synchronized boolean truncated() {
            return total > OUTPUT_LIMIT;
        }
    }
}
