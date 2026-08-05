package cn.lunalhx.ai.infrastructure.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Atomic file writes: write to a temp file in the same directory, then move.
 * Platforms without atomic move fall back to a safe replace.
 */
final class AtomicFiles {

    private AtomicFiles() {
    }

    static void write(Path target, byte[] payload) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path temp = Files.createTempFile(dir, target.getFileName().toString() + ".", ".tmp");
        try {
            Files.write(temp, payload);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static void writeText(Path target, String content) throws IOException {
        write(target, content.getBytes(StandardCharsets.UTF_8));
    }
}
