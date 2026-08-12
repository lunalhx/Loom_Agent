package cn.lunalhx.ai.domain.tool.model;

import java.nio.file.Path;
import java.util.Objects;

/** Canonical external filesystem access requested by one shell invocation. */
public record ExecutionGrantRequest(Path canonicalPath, FilesystemAccess access) {
    public ExecutionGrantRequest {
        canonicalPath = Objects.requireNonNull(canonicalPath, "canonicalPath must not be null");
        access = Objects.requireNonNull(access, "access must not be null");
    }
}
