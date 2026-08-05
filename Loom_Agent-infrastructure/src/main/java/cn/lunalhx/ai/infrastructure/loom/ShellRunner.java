package cn.lunalhx.ai.infrastructure.loom;

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

    private static final Set<String> ALLOWED_ENV_KEYS = Set.of(
            "HOME", "LANG", "LC_ALL", "LC_CTYPE", "LOGNAME", "PATH", "PWD",
            "SHELL", "TERM", "TMPDIR", "TMP", "TEMP", "USER");

    private static final Pattern SENSITIVE_SUFFIX = Pattern.compile("(API_KEY|TOKEN|SECRET|PASSWORD)$");

    public record ShellResult(int exitCode, String stdout, String stderr) {
    }

    private ShellRunner() {
    }

    public static ShellResult run(String command, Path cwd, int timeoutSeconds, Set<String> secretEnvNames) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : ALLOWED_ENV_KEYS) {
            String value = System.getenv(key);
            if (value != null) {
                env.put(key, value);
            }
        }

        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", command)
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

        try {
            Process process = builder.start();
            List<Thread> readers = new ArrayList<>();
            StringBuilderOut stdout = new StringBuilderOut();
            StringBuilderOut stderr = new StringBuilderOut();
            readers.add(startReader(process.getInputStream(), stdout));
            readers.add(startReader(process.getErrorStream(), stderr));
            boolean completed = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
            for (Thread t : readers) {
                t.join(1000);
            }
            int code = completed ? process.exitValue() : -1;
            return new ShellResult(code, stdout.value(), stderr.value());
        } catch (IOException e) {
            return new ShellResult(-1, "", "error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ShellResult(-1, "", "error: interrupted");
        }
    }

    private static boolean isSensitive(String key, Set<String> extra) {
        String upper = key.toUpperCase();
        if (extra != null && extra.contains(upper)) {
            return true;
        }
        return SENSITIVE_SUFFIX.matcher(upper).find();
    }

    private static Thread startReader(InputStream in, StringBuilderOut out) {
        Thread t = new Thread(() -> {
            try (InputStream is = in) {
                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) >= 0) {
                    out.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static final class StringBuilderOut {
        private final StringBuilder sb = new StringBuilder();

        synchronized void append(String chunk) {
            sb.append(chunk);
        }

        synchronized String value() {
            return sb.toString();
        }
    }
}
