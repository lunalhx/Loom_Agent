package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.model.ExecutionGrant;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfileKind;
import cn.lunalhx.ai.domain.tool.model.FilesystemAccess;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;

/** Validates a run_shell external-access request against immutable execution grants. */
final class ExecutionGrantValidator {
    private static final int MAX_EXTERNAL_ACCESS = 16;

    void validate(String toolName, JsonNode input, ExecutionProfile profile) {
        if (!"run_shell".equals(toolName) || input == null || !input.has("external_access")) return;
        JsonNode requested = input.path("external_access");
        if (!requested.isArray() || requested.size() > MAX_EXTERNAL_ACCESS) {
            throw new IllegalArgumentException("external_access must contain at most 16 entries");
        }
        if (profile.kind() != ExecutionProfileKind.BUILD_SANDBOX) {
            throw new IllegalArgumentException("external_access is only available to ordinary Build runs");
        }
        for (JsonNode entry : requested) validateEntry(entry, profile);
    }

    private void validateEntry(JsonNode entry, ExecutionProfile profile) {
        if (!entry.isObject() || entry.size() != 2 || !entry.path("path").isTextual()
                || !entry.path("access").isTextual()) {
            throw new IllegalArgumentException("external_access entry requires only path and access");
        }
        FilesystemAccess requestedAccess;
        try {
            requestedAccess = FilesystemAccess.valueOf(entry.path("access").asText().toUpperCase());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("external_access access must be read or write");
        }
        final Path target;
        try {
            target = Path.of(entry.path("path").asText()).toRealPath();
        } catch (Exception invalid) {
            throw new IllegalArgumentException("external_access path must exist and resolve canonically");
        }
        if (!Files.isRegularFile(target) && !Files.isDirectory(target)) {
            throw new IllegalArgumentException("external_access target must be a regular file or directory");
        }
        boolean granted = profile.externalGrants().stream().anyMatch(grant -> covers(grant, target, requestedAccess));
        if (!granted) throw new IllegalArgumentException("external_access is not covered by an execution grant");
    }

    private boolean covers(ExecutionGrant grant, Path target, FilesystemAccess requested) {
        return grant.access().includes(requested) && target.startsWith(grant.canonicalPath());
    }
}
