package cn.lunalhx.ai.domain.tool.model;

import java.util.Objects;

/** Exact-call permission grant; durable representations contain only a salted digest. */
public record PermissionGrant(String saltedCallDigest, String salt,
                              ExecutionProfile executionProfile, GrantLifetime lifetime) {
    public PermissionGrant {
        saltedCallDigest = Objects.requireNonNull(saltedCallDigest, "saltedCallDigest must not be null");
        salt = Objects.requireNonNull(salt, "salt must not be null");
        executionProfile = Objects.requireNonNull(executionProfile, "executionProfile must not be null");
        lifetime = Objects.requireNonNull(lifetime, "lifetime must not be null");
    }
}
