package cn.lunalhx.ai.domain.tool.model;

import java.util.Objects;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Exact-call permission grant; durable representations contain only a salted digest. */
public record PermissionGrant(String saltedCallDigest, String salt,
                              ExecutionProfile executionProfile, GrantLifetime lifetime) {
    public PermissionGrant {
        saltedCallDigest = Objects.requireNonNull(saltedCallDigest, "saltedCallDigest must not be null");
        salt = Objects.requireNonNull(salt, "salt must not be null");
        executionProfile = Objects.requireNonNull(executionProfile, "executionProfile must not be null");
        lifetime = Objects.requireNonNull(lifetime, "lifetime must not be null");
    }

    /** Creates a grant without retaining the normalized command or its direct digest. */
    public static PermissionGrant issue(String exactCallKey, ExecutionProfile profile,
                                        GrantLifetime lifetime) {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String salt = HexFormat.of().formatHex(random);
        return new PermissionGrant(digest(salt + "\n" + exactCallKey), salt, profile, lifetime);
    }

    public boolean matches(String exactCallKey, ExecutionProfile activeProfile) {
        return saltedCallDigest.equals(digest(salt + "\n" + exactCallKey))
                && activeProfile != null
                && activeProfile.isSameOrStricterThan(executionProfile);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
