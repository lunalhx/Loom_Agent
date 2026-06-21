package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentWorkspace;
import cn.lunalhx.ai.domain.agent.model.valobj.WorkspaceResolutionException;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class AgentWorkspaceResolver {

    private final Path defaultWorkspace;
    private final List<Path> allowedRoots;

    public AgentWorkspaceResolver(AgentRuntimeProperties properties) {
        this.defaultWorkspace = toExistingDirectory(properties.getWorkspaceRoot(), "WORKSPACE_NOT_FOUND");
        this.allowedRoots = resolveAllowedRoots(properties);
        validateWorkspace(defaultWorkspace);
    }

    public AgentWorkspace resolve(String requestWorkspace) {
        if (StringUtils.isBlank(requestWorkspace)) {
            return AgentWorkspace.builder()
                    .root(defaultWorkspace)
                    .displayName(displayName(defaultWorkspace))
                    .workspace(WorkspaceRef.local(defaultWorkspace, displayName(defaultWorkspace)))
                    .build();
        }
        String trimmed = StringUtils.trim(requestWorkspace);
        Path rawPath = Paths.get(trimmed);
        if (rawPath.isAbsolute()) {
            Path workspace = toExistingDirectory(trimmed, "WORKSPACE_NOT_FOUND");
            validateWorkspace(workspace);
            return AgentWorkspace.builder()
                    .root(workspace)
                    .displayName(displayName(workspace))
                    .workspace(WorkspaceRef.local(workspace, displayName(workspace)))
                    .build();
        }

        WorkspaceResolutionException lastNotAllowed = null;
        WorkspaceResolutionException specificFailure = null;
        for (Path allowedRoot : allowedRoots) {
            Path candidate = allowedRoot.resolve(trimmed).normalize();
            try {
                Path workspace = toExistingDirectory(candidate.toString(), "WORKSPACE_NOT_FOUND");
                validateWorkspace(workspace);
                return AgentWorkspace.builder()
                        .root(workspace)
                        .displayName(displayName(workspace))
                        .workspace(WorkspaceRef.local(workspace, displayName(workspace)))
                        .build();
            } catch (WorkspaceResolutionException e) {
                if ("WORKSPACE_NOT_ALLOWED".equals(e.getCode()) || "WORKSPACE_PATH_ESCAPE".equals(e.getCode())) {
                    lastNotAllowed = e;
                } else if (!"WORKSPACE_NOT_FOUND".equals(e.getCode()) && specificFailure == null) {
                    specificFailure = e;
                }
            }
        }
        if (lastNotAllowed != null) {
            throw lastNotAllowed;
        }
        if (specificFailure != null) {
            throw specificFailure;
        }
        throw new WorkspaceResolutionException("WORKSPACE_NOT_FOUND", "工作区不存在：" + requestWorkspace);
    }

    public AgentWorkspace getDefaultWorkspace() {
        return AgentWorkspace.builder()
                .root(defaultWorkspace)
                .displayName(displayName(defaultWorkspace))
                .workspace(WorkspaceRef.local(defaultWorkspace, displayName(defaultWorkspace)))
                .build();
    }

    public List<Path> getAllowedRoots() {
        return allowedRoots;
    }

    public void validateWorkspace(Path workspace) {
        Path realWorkspace = toExistingDirectory(workspace.toString(), "WORKSPACE_NOT_FOUND");
        if (isTooBroad(realWorkspace) && allowedRoots.stream().noneMatch(realWorkspace::equals)) {
            throw new WorkspaceResolutionException("WORKSPACE_NOT_ALLOWED", "工作区路径过宽：" + realWorkspace);
        }
        boolean allowed = allowedRoots.stream().anyMatch(allowedRoot ->
                realWorkspace.equals(allowedRoot) || realWorkspace.startsWith(allowedRoot));
        if (!allowed) {
            throw new WorkspaceResolutionException("WORKSPACE_NOT_ALLOWED", "工作区不在允许根目录下：" + workspace);
        }
    }

    private List<Path> resolveAllowedRoots(AgentRuntimeProperties properties) {
        List<String> configuredRoots = properties.getAllowedWorkspaceRoots();
        List<String> roots = configuredRoots == null || configuredRoots.isEmpty()
                ? List.of(properties.getWorkspaceRoot())
                : configuredRoots;
        List<Path> resolved = new ArrayList<>();
        for (String root : roots) {
            resolved.add(toExistingDirectory(root, "WORKSPACE_NOT_FOUND"));
        }
        return List.copyOf(resolved);
    }

    private Path toExistingDirectory(String rawPath, String notFoundCode) {
        try {
            Path path = Paths.get(rawPath).toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                throw new WorkspaceResolutionException(notFoundCode, "工作区不存在：" + rawPath);
            }
            Path realPath = path.toRealPath();
            if (!Files.isDirectory(realPath)) {
                throw new WorkspaceResolutionException("WORKSPACE_NOT_DIRECTORY", "工作区不是目录：" + rawPath);
            }
            return realPath;
        } catch (WorkspaceResolutionException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkspaceResolutionException("WORKSPACE_PATH_ESCAPE", "工作区路径解析失败：" + rawPath);
        }
    }

    private boolean isTooBroad(Path workspace) {
        Path home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        return workspace.getParent() == null || workspace.equals(home);
    }

    private String displayName(Path workspace) {
        for (Path allowedRoot : allowedRoots) {
            if (workspace.equals(allowedRoot)) {
                Path fileName = workspace.getFileName();
                return fileName == null ? workspace.toString() : fileName.toString();
            }
            if (workspace.startsWith(allowedRoot)) {
                return allowedRoot.relativize(workspace).toString();
            }
        }
        Path fileName = workspace.getFileName();
        return fileName == null ? workspace.toString() : fileName.toString();
    }

}
