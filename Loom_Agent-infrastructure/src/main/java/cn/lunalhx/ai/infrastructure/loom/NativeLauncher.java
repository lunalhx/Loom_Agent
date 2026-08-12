package cn.lunalhx.ai.infrastructure.loom;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Fixed native pre-exec handshake; user commands are argv data only. */
final class NativeLauncher {
    static final byte[] READY = "LOOM_LAUNCHER_READY\n".getBytes(StandardCharsets.US_ASCII);

    private NativeLauncher() { }

    static List<String> wrap(List<String> target) {
        List<String> argv = new ArrayList<>();
        argv.add(binary().toString());
        argv.addAll(target);
        return List.copyOf(argv);
    }

    static boolean awaitReadyAndRelease(Process process, PosixProcessSupervisor supervisor) throws IOException {
        InputStream stdout = process.getInputStream();
        byte[] marker = stdout.readNBytes(READY.length);
        return java.util.Arrays.equals(marker, READY) && supervisor.release(process);
    }

    private static Path binary() {
        try {
            URL resource = NativeLauncher.class.getResource("/native/loom-launcher");
            if (resource == null) throw new IllegalStateException("native launcher resource is missing");
            if ("file".equals(resource.getProtocol())) return Path.of(resource.toURI());
            Path copied = Files.createTempFile("loom-launcher-", "");
            try (InputStream source = resource.openStream()) { Files.copy(source, copied, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            Files.setPosixFilePermissions(copied, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE));
            copied.toFile().deleteOnExit();
            return copied;
        } catch (Exception e) {
            throw new IllegalStateException("native launcher is unavailable", e);
        }
    }
}
