package cn.lunalhx.ai.domain.tool.sandbox;

import java.nio.file.Path;
import java.util.Set;

public record SandboxRequest(Path workspace,
                             String conversationId,
                             long maxExecutionMs,
                             Set<String> allowedAdditionalEnvironmentKeys) {

    public SandboxRequest {
        if (workspace == null || conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("workspace and conversationId are required");
        }
        workspace = workspace.toAbsolutePath().normalize();
        allowedAdditionalEnvironmentKeys = allowedAdditionalEnvironmentKeys == null
                ? Set.of() : Set.copyOf(allowedAdditionalEnvironmentKeys);
        if (maxExecutionMs <= 0) {
            throw new IllegalArgumentException("maxExecutionMs must be positive");
        }
    }
}
