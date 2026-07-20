package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.tool.model.BackgroundLaunchMode;
import cn.lunalhx.ai.domain.tool.model.ShellOutputLimits;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.sandbox.PathMapping;
import cn.lunalhx.ai.domain.tool.sandbox.Sandbox;
import cn.lunalhx.ai.domain.tool.sandbox.SandboxBackgroundResult;
import cn.lunalhx.ai.domain.tool.sandbox.SandboxRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LocalSandbox implements Sandbox {

    private final SandboxRequest request;
    private final BackgroundProcessManager processManager;
    private final PathMapping workspaceMapping;
    private final Path sessionTemp;
    private final Path uploads;
    private final Path outputs;

    public LocalSandbox(SandboxRequest request,
                        BackgroundProcessManager processManager,
                        Path managedRoot,
                        Path sessionTemp,
                        Path uploads,
                        Path outputs) throws IOException {
        Path workspace = request.workspace().toRealPath();
        this.request = new SandboxRequest(workspace, request.conversationId(), request.maxExecutionMs(),
                request.allowedAdditionalEnvironmentKeys());
        this.processManager = processManager;
        this.workspaceMapping = new PathMapping(workspace, Path.of("/workspace"));
        this.sessionTemp = create(managedRoot, sessionTemp);
        this.uploads = create(managedRoot, uploads);
        this.outputs = create(managedRoot, outputs);
    }

    @Override
    public PathMapping workspaceMapping() {
        return workspaceMapping;
    }

    @Override
    public Path sessionTemp() {
        return sessionTemp;
    }

    @Override
    public Path uploads() {
        return uploads;
    }

    @Override
    public Path outputs() {
        return outputs;
    }

    @Override
    public ToolResult execute(List<String> command, Path cwd, Map<String, String> extraEnv,
                              long timeoutMs, ShellOutputLimits limits, long startedAt) {
        try {
            Path safeCwd = WorkspacePathSanitizer.directory(request.workspace(), cwd.toString());
            Map<String, String> safeEnv = allowedEnv(extraEnv);
            long boundedTimeout = Math.min(Math.max(1, timeoutMs), request.maxExecutionMs());
            BackgroundProcessManager.SyncResult result = processManager.runSync(
                    command, safeCwd, safeEnv, boundedTimeout, limits, startedAt, request.conversationId());
            return ToolResult.builder()
                    .success(result.success())
                    .errorCode(result.errorCode())
                    .message(result.message())
                    .observation(result.observation())
                    .truncated(result.truncated())
                    .elapsedMs(result.elapsedMs())
                    .build();
        } catch (Exception e) {
            return ToolResult.failure("sandbox_path_rejected", e.getMessage(),
                    System.currentTimeMillis() - startedAt);
        }
    }

    @Override
    public SandboxBackgroundResult startBackground(List<String> command, Path cwd,
                                                   Map<String, String> extraEnv, long timeoutMs,
                                                   String runId, String workspaceDisplayName,
                                                   BackgroundLaunchMode launchMode) {
        try {
            Path safeCwd = WorkspacePathSanitizer.directory(request.workspace(), cwd.toString());
            var result = processManager.startBackground(command, safeCwd, allowedEnv(extraEnv),
                    Math.min(Math.max(1, timeoutMs), request.maxExecutionMs()), runId,
                    request.conversationId(), workspaceDisplayName, launchMode,
                    create(sessionTemp, sessionTemp.resolve("background")));
            return new SandboxBackgroundResult(result.started(), result.errorCode(), result.message(), result.task());
        } catch (Exception e) {
            return new SandboxBackgroundResult(false, "sandbox_path_rejected", e.getMessage(), null);
        }
    }

    @Override
    public boolean hasActiveProcesses() {
        return processManager.activeProcessCountForConversation(request.conversationId()) > 0;
    }

    @Override
    public void cancelProcesses() {
        processManager.cancelAllProcessesForConversation(request.conversationId());
    }

    private Map<String, String> allowedEnv(Map<String, String> extraEnv) {
        if (extraEnv == null || extraEnv.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        extraEnv.forEach((key, value) -> {
            if (!request.allowedAdditionalEnvironmentKeys().contains(key)) {
                throw new IllegalArgumentException("Additional environment key is not allowed: " + key);
            }
            result.put(key, value);
        });
        return Map.copyOf(result);
    }

    private static Path create(Path managedRoot, Path path) throws IOException {
        Files.createDirectories(managedRoot);
        Path configuredRoot = managedRoot.toAbsolutePath().normalize();
        Path root = managedRoot.toRealPath();
        Path target = path.toAbsolutePath().normalize();
        if (!target.startsWith(configuredRoot)) {
            throw new IOException("Sandbox session path is outside the managed root");
        }
        Path ancestor = target;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null || !ancestor.toRealPath().startsWith(root)) {
            throw new IOException("Sandbox session path escapes through a symbolic link");
        }
        Files.createDirectories(target);
        Path realTarget = target.toRealPath();
        if (!realTarget.startsWith(root)) {
            throw new IOException("Sandbox session path escapes through a symbolic link");
        }
        return realTarget;
    }
}
