package cn.lunalhx.ai.domain.tool.sandbox;

import java.nio.file.Path;

public record PathMapping(Path hostPath, Path sandboxPath) {

    public PathMapping {
        hostPath = hostPath.toAbsolutePath().normalize();
        sandboxPath = sandboxPath.toAbsolutePath().normalize();
    }

    public Path toHost(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(sandboxPath)) {
            throw new IllegalArgumentException("Sandbox path is outside mapping: " + path);
        }
        return hostPath.resolve(sandboxPath.relativize(normalized)).normalize();
    }

    public Path toSandbox(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(hostPath)) {
            throw new IllegalArgumentException("Host path is outside mapping: " + path);
        }
        return sandboxPath.resolve(hostPath.relativize(normalized)).normalize();
    }
}
