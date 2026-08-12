package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.model.GrantLifetime;
import cn.lunalhx.ai.domain.tool.model.PermissionGrant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Durable, exact-call workspace grants stored outside the controlled workspace. */
public final class WorkspacePermissionGrantStore {
    private static final int VERSION = 1;
    private final Path loomHome;
    private final ObjectMapper mapper;

    public WorkspacePermissionGrantStore() {
        this(defaultLoomHome(), new ObjectMapper());
    }

    WorkspacePermissionGrantStore(Path loomHome, ObjectMapper mapper) {
        this.loomHome = loomHome;
        this.mapper = mapper;
    }

    public List<PermissionGrant> load(Path canonicalWorkspace) {
        Path file = grantsFile(canonicalWorkspace);
        if (!Files.exists(file)) return List.of();
        try {
            if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("workspace grants must not be a symlink");
            return parse(Files.readAllBytes(file), file);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read workspace grants " + file + ": " + e.getMessage(), e);
        }
    }

    /** Appends atomically; an inability to persist is an authorization failure. */
    public void append(Path canonicalWorkspace, PermissionGrant grant) {
        if (grant.lifetime() != GrantLifetime.WORKSPACE) {
            throw new IllegalArgumentException("only workspace grants may be persisted");
        }
        Path file = grantsFile(canonicalWorkspace);
        Path parent = file.getParent();
        try {
            Files.createDirectories(parent);
            Path lockPath = parent.resolve("grants.lock");
            try (FileChannel channel = FileChannel.open(lockPath,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                List<PermissionGrant> grants = new ArrayList<>(load(canonicalWorkspace));
                grants.add(grant);
                byte[] data = mapper.writeValueAsBytes(new GrantDocument(VERSION, grants));
                Path temporary = Files.createTempFile(parent, "grants-", ".tmp");
                try {
                    Files.write(temporary, data);
                    try {
                        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot persist workspace grant " + file + ": " + e.getMessage(), e);
        }
    }

    Path workspaceDirectory(Path canonicalWorkspace) {
        return loomHome.resolve("workspaces").resolve(digest(canonicalWorkspace.toString()));
    }

    Path policyFile(Path canonicalWorkspace) {
        return workspaceDirectory(canonicalWorkspace).resolve("permissions.yml");
    }

    private Path grantsFile(Path canonicalWorkspace) {
        return workspaceDirectory(canonicalWorkspace).resolve("grants.json");
    }

    private List<PermissionGrant> parse(byte[] data, Path file) {
        try {
            JsonNode root = mapper.readTree(data);
            if (root == null || !root.isObject() || root.path("version").asInt(-1) != VERSION
                    || !root.path("grants").isArray()) {
                throw new IllegalArgumentException("workspace grants require version: 1 and grants array");
            }
            List<PermissionGrant> result = new ArrayList<>();
            for (JsonNode item : root.path("grants")) {
                PermissionGrant grant = mapper.treeToValue(item, PermissionGrant.class);
                if (grant.lifetime() != GrantLifetime.WORKSPACE) {
                    throw new IllegalArgumentException("workspace grant has invalid lifetime");
                }
                result.add(grant);
            }
            return List.copyOf(result);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid workspace grants " + file + ": " + e.getMessage(), e);
        }
    }

    private static Path defaultLoomHome() {
        String configured = System.getenv("XDG_CONFIG_HOME");
        if (configured != null && !configured.isBlank()) return Path.of(configured).resolve("loom-code");
        return Path.of(System.getProperty("user.home"), ".config", "loom-code");
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record GrantDocument(int version, List<PermissionGrant> grants) { }
}
