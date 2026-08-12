package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.tool.model.ExecutionGrant;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.FilesystemAccess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Linux/WSL2 empty-root, offline Bubblewrap backend. */
final class BubblewrapSandboxBackend {
    private static final Path BWRAP = Path.of("/usr/bin/bwrap");
    private static final AtomicReference<Boolean> AVAILABLE = new AtomicReference<>();

    private BubblewrapSandboxBackend() { }

    static boolean supported() {
        Boolean cached = AVAILABLE.get();
        if (cached != null) return cached;
        boolean available = probe();
        AVAILABLE.compareAndSet(null, available);
        return AVAILABLE.get();
    }

    static List<String> command(ExecutionProfile profile, List<String> target) {
        if (profile.workspace() == null) throw new IllegalArgumentException("sandbox workspace is required");
        List<String> argv = new ArrayList<>(List.of(BWRAP.toString(), "--die-with-parent", "--new-session",
                "--unshare-net", "--unshare-pid", "--unshare-ipc", "--proc", "/proc", "--dev", "/dev"));
        for (String root : List.of("/usr", "/bin", "/lib", "/lib64")) {
            if (Files.exists(Path.of(root))) argv.addAll(List.of("--ro-bind", root, root));
        }
        bind(argv, profile.workspace(), profile.workspace(), profile.workspaceAccess());
        if (profile.temporaryRoot() != null) bind(argv, profile.temporaryRoot(), profile.temporaryRoot(), FilesystemAccess.WRITE);
        for (ExecutionGrant grant : profile.externalGrants()) bind(argv, grant.canonicalPath(), grant.canonicalPath(), grant.access());
        argv.addAll(List.of("--chdir", profile.workspace().toString()));
        argv.addAll(target);
        return List.copyOf(argv);
    }

    private static void bind(List<String> argv, Path source, Path target, FilesystemAccess access) {
        argv.add(access == FilesystemAccess.WRITE ? "--bind" : "--ro-bind");
        argv.add(source.toString());
        argv.add(target.toString());
    }

    private static boolean probe() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux") || !Files.isExecutable(BWRAP)) return false;
        try {
            Process probe = new ProcessBuilder(BWRAP.toString(), "--unshare-net", "--proc", "/proc", "--dev", "/dev",
                    "--ro-bind", "/usr", "/usr", "/usr/bin/true").start();
            return probe.waitFor(2, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (Exception unavailable) { return false; }
    }
}
