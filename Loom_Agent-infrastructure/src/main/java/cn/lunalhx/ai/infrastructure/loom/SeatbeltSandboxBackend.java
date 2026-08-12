package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** macOS ordinary-build backend.  A launch failure never falls back to host execution. */
final class SeatbeltSandboxBackend {
    private static final Path SANDBOX_EXEC = Path.of("/usr/bin/sandbox-exec");
    private static final AtomicReference<Boolean> AVAILABLE = new AtomicReference<>();

    private SeatbeltSandboxBackend() { }

    static boolean supported() {
        Boolean cached = AVAILABLE.get();
        if (cached != null) return cached;
        boolean available = probe();
        AVAILABLE.compareAndSet(null, available);
        return AVAILABLE.get();
    }

    private static boolean probe() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")
                || !Files.isExecutable(SANDBOX_EXEC)) return false;
        try {
            Process probe = new ProcessBuilder(SANDBOX_EXEC.toString(), "-p",
                    "(version 1) (deny default) (allow process-exec) (allow file-read* (subpath \"/System\") (subpath \"/usr\"))",
                    "/usr/bin/true").start();
            return probe.waitFor(2, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (Exception unavailable) {
            return false;
        }
    }

    static String policy(ExecutionProfile profile) {
        Path workspace = profile.workspace();
        if (workspace == null) throw new IllegalArgumentException("sandbox workspace is required");
        String escapedWorkspace = quote(workspace);
        StringBuilder policy = new StringBuilder("(version 1) (deny default) ")
                .append("(allow process-exec) (allow process-fork) ")
                .append("(allow file-read* (subpath \"/System\") (subpath \"/usr\") (subpath \"/bin\")) ")
                .append("(allow file-read* (subpath ").append(escapedWorkspace).append(")) ");
        if (profile.workspaceAccess().includes(cn.lunalhx.ai.domain.tool.model.FilesystemAccess.WRITE)) {
            policy.append("(allow file-write* (subpath ").append(escapedWorkspace).append(")) ");
        }
        if (profile.temporaryRoot() != null) {
            String temporary = quote(profile.temporaryRoot());
            policy.append("(allow file-read* (subpath ").append(temporary).append(")) ")
                    .append("(allow file-write* (subpath ").append(temporary).append(")) ");
        }
        if (profile.homeRoot() != null) {
            String home = quote(profile.homeRoot());
            policy.append("(allow file-read* (subpath ").append(home).append(")) ")
                    .append("(allow file-write* (subpath ").append(home).append(")) ");
        }
        for (cn.lunalhx.ai.domain.tool.model.ExecutionGrant grant : profile.externalGrants()) {
            String external = quote(grant.canonicalPath());
            policy.append("(allow file-read* (subpath ").append(external).append(")) ");
            if (grant.access().includes(cn.lunalhx.ai.domain.tool.model.FilesystemAccess.WRITE)) {
                policy.append("(allow file-write* (subpath ").append(external).append(")) ");
            }
        }
        return policy.toString();
    }

    private static String quote(Path path) {
        return "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
