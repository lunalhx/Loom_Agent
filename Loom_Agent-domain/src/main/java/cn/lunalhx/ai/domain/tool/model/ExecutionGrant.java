package cn.lunalhx.ai.domain.tool.model;

import java.nio.file.Path;
import java.util.Objects;

/** A canonical, regular-file-or-directory external filesystem capability. */
public record ExecutionGrant(Path canonicalPath, FilesystemAccess access,
                             GrantLifetime lifetime) {
    public ExecutionGrant {
        canonicalPath = Objects.requireNonNull(canonicalPath, "canonicalPath must not be null");
        access = Objects.requireNonNull(access, "access must not be null");
        lifetime = Objects.requireNonNull(lifetime, "lifetime must not be null");
    }
}
