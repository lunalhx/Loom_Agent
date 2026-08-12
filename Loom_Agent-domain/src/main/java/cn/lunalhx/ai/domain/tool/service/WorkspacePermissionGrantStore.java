package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.model.GrantLifetime;
import cn.lunalhx.ai.domain.tool.model.PermissionGrant;
import cn.lunalhx.ai.domain.tool.model.ExecutionGrant;
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
            return parse(Files.readAllBytes(file), file).permissionGrants();
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
                GrantDocument current = readDocument(file);
                List<PermissionGrant> grants = new ArrayList<>(current.permissionGrants());
                grants.add(grant);
                writeDocument(file, new GrantDocument(VERSION, grants, current.executionGrants()));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot persist workspace grant " + file + ": " + e.getMessage(), e);
        }
    }

    public List<ExecutionGrant> loadExecution(Path canonicalWorkspace) {
        Path file = grantsFile(canonicalWorkspace);
        if (!Files.exists(file)) return List.of();
        try {
            if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("workspace grants must not be a symlink");
            return parse(Files.readAllBytes(file), file).executionGrants();
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read workspace grants " + file + ": " + e.getMessage(), e);
        }
    }

    public void appendExecution(Path canonicalWorkspace, ExecutionGrant grant) {
        if (grant.lifetime() != GrantLifetime.WORKSPACE) {
            throw new IllegalArgumentException("only workspace grants may be persisted");
        }
        Path file = grantsFile(canonicalWorkspace);
        Path parent = file.getParent();
        try {
            Files.createDirectories(parent);
            try (FileChannel channel = FileChannel.open(parent.resolve("grants.lock"),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                GrantDocument current = readDocument(file);
                List<ExecutionGrant> grants = new ArrayList<>(current.executionGrants());
                grants.add(grant);
                writeDocument(file, new GrantDocument(VERSION, current.permissionGrants(), grants));
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

    private GrantDocument readDocument(Path file) throws IOException {
        return Files.exists(file) ? parse(Files.readAllBytes(file), file)
                : new GrantDocument(VERSION, List.of(), List.of());
    }

    private void writeDocument(Path file, GrantDocument document) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "grants-", ".tmp");
        try {
            Files.write(temporary, mapper.writeValueAsBytes(document));
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private GrantDocument parse(byte[] data, Path file) {
        try {
            JsonNode root = mapper.readTree(data);
            if (root == null || !root.isObject() || root.path("version").asInt(-1) != VERSION
                    || !root.path("permission_grants").isArray() || !root.path("execution_grants").isArray()) {
                throw new IllegalArgumentException("workspace grants require version: 1, permission_grants and execution_grants arrays");
            }
            List<PermissionGrant> permissions = new ArrayList<>();
            for (JsonNode item : root.path("permission_grants")) {
                PermissionGrant grant = mapper.treeToValue(item, PermissionGrant.class);
                if (grant.lifetime() != GrantLifetime.WORKSPACE) {
                    throw new IllegalArgumentException("workspace grant has invalid lifetime");
                }
                permissions.add(grant);
            }
            List<ExecutionGrant> executions = new ArrayList<>();
            for (JsonNode item : root.path("execution_grants")) {
                ExecutionGrant grant = mapper.treeToValue(item, ExecutionGrant.class);
                if (grant.lifetime() != GrantLifetime.WORKSPACE) {
                    throw new IllegalArgumentException("workspace execution grant has invalid lifetime");
                }
                executions.add(grant);
            }
            return new GrantDocument(VERSION, List.copyOf(permissions), List.copyOf(executions));
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

    private record GrantDocument(int version, List<PermissionGrant> permission_grants,
                                 List<ExecutionGrant> execution_grants) {
        List<PermissionGrant> permissionGrants() { return permission_grants; }
        List<ExecutionGrant> executionGrants() { return execution_grants; }
    }
}
