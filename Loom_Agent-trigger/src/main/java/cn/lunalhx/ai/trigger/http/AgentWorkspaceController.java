package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.dto.AgentWorkspaceRequest;
import cn.lunalhx.ai.api.dto.AgentWorkspaceResponse;
import cn.lunalhx.ai.api.dto.AgentWorkspaceTreeNode;
import cn.lunalhx.ai.api.dto.AgentWorkspaceTreeRequest;
import cn.lunalhx.ai.api.dto.AgentWorkspaceTreeResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentWorkspace;
import cn.lunalhx.ai.domain.agent.model.valobj.WorkspaceResolutionException;
import cn.lunalhx.ai.domain.agent.service.AgentWorkspaceResolver;
import cn.lunalhx.ai.types.enums.ResponseCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/agent/code/workspaces")
public class AgentWorkspaceController {

    private static final int DEFAULT_LIMIT = 200;
    private static final Set<String> DEFAULT_IGNORED_NAMES = Set.of(".git", "target", "node_modules", "build");

    private final AgentWorkspaceResolver workspaceResolver;
    private final Validator validator;

    @PostMapping("/resolve")
    public Response<AgentWorkspaceResponse> resolve(@RequestBody(required = false) AgentWorkspaceRequest request) {
        AgentWorkspaceRequest safeRequest = request == null ? new AgentWorkspaceRequest() : request;
        Response<AgentWorkspaceResponse> validationError = validate(safeRequest);
        if (validationError != null) {
            return validationError;
        }
        try {
            AgentWorkspace workspace = workspaceResolver.resolve(safeRequest.getWorkspace());
            return success(AgentWorkspaceResponse.builder()
                    .workspace(workspace.getRoot().toString())
                    .displayName(workspace.getDisplayName())
                    .defaultWorkspace(workspaceResolver.getDefaultWorkspace().getRoot().toString())
                    .allowedRoots(workspaceResolver.getAllowedRoots().stream()
                            .map(Path::toString)
                            .collect(Collectors.toList()))
                    .sandboxRoot(true)
                    .message("工作目录已校验，并作为本次会话沙箱根目录；新项目需位于 allowedRoots 之一下面")
                    .build());
        } catch (WorkspaceResolutionException e) {
            return illegal(e.getMessage());
        }
    }

    @PostMapping("/tree")
    public Response<AgentWorkspaceTreeResponse> tree(@RequestBody(required = false) AgentWorkspaceTreeRequest request) {
        AgentWorkspaceTreeRequest safeRequest = request == null ? new AgentWorkspaceTreeRequest() : request;
        Response<AgentWorkspaceTreeResponse> validationError = validate(safeRequest);
        if (validationError != null) {
            return validationError;
        }
        try {
            AgentWorkspace workspace = workspaceResolver.resolve(safeRequest.getWorkspace());
            Path target = resolveInsideWorkspace(workspace.getRoot(), safeRequest.getPath());
            if (!Files.isDirectory(target)) {
                return illegal("path 不是目录：" + StringUtils.defaultIfBlank(safeRequest.getPath(), "."));
            }
            TreeBuildResult result = directoryNode(workspace.getRoot(), target, limit(safeRequest.getLimit()), true);
            return success(AgentWorkspaceTreeResponse.builder()
                    .workspace(workspace.getRoot().toString())
                    .displayName(workspace.getDisplayName())
                    .path(relative(workspace.getRoot(), target))
                    .node(result.node())
                    .truncated(result.truncated())
                    .build());
        } catch (WorkspaceResolutionException e) {
            return illegal(e.getMessage());
        } catch (IOException | IllegalArgumentException e) {
            return illegal(e.getMessage());
        }
    }

    @PostMapping("/browse")
    public Response<AgentWorkspaceTreeResponse> browse(@RequestBody(required = false) AgentWorkspaceTreeRequest request) {
        AgentWorkspaceTreeRequest safeRequest = request == null ? new AgentWorkspaceTreeRequest() : request;
        Response<AgentWorkspaceTreeResponse> validationError = validate(safeRequest);
        if (validationError != null) {
            return validationError;
        }
        try {
            AgentWorkspace workspace = workspaceResolver.resolve(safeRequest.getWorkspace());
            Path target = resolveInsideWorkspace(workspace.getRoot(), safeRequest.getPath());
            if (!Files.isDirectory(target)) {
                return illegal("path 不是目录：" + StringUtils.defaultIfBlank(safeRequest.getPath(), "."));
            }
            TreeBuildResult result = directoryNode(workspace.getRoot(), target, limit(safeRequest.getLimit()), false);
            List<AgentWorkspaceTreeNode> folders = result.node().getChildren().stream()
                    .filter(node -> "directory".equals(node.getType()))
                    .collect(Collectors.toList());
            result.node().setChildren(folders);
            return success(AgentWorkspaceTreeResponse.builder()
                    .workspace(workspace.getRoot().toString())
                    .displayName(workspace.getDisplayName())
                    .path(relative(workspace.getRoot(), target))
                    .node(result.node())
                    .truncated(result.truncated())
                    .build());
        } catch (WorkspaceResolutionException e) {
            return illegal(e.getMessage());
        } catch (IOException | IllegalArgumentException e) {
            return illegal(e.getMessage());
        }
    }

    private TreeBuildResult directoryNode(Path root, Path directory, int limit, boolean includeFiles) throws IOException {
        List<IgnoreRule> ignoreRules = readIgnoreRules(root);
        List<AgentWorkspaceTreeNode> children = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                Path realChild = child.toRealPath();
                if (!realChild.startsWith(root) || ignored(root, realChild, ignoreRules)) {
                    continue;
                }
                boolean directoryChild = Files.isDirectory(realChild);
                if (!includeFiles && !directoryChild) {
                    continue;
                }
                if (children.size() >= limit) {
                    truncated = true;
                    break;
                }
                children.add(toNode(root, realChild, directoryChild));
            }
        }
        children.sort(Comparator
                .comparing((AgentWorkspaceTreeNode node) -> !"directory".equals(node.getType()))
                .thenComparing(node -> node.getName().toLowerCase(Locale.ROOT)));
        return new TreeBuildResult(toNode(root, directory, true, children), truncated);
    }

    private AgentWorkspaceTreeNode toNode(Path root, Path path, boolean directory) throws IOException {
        return toNode(root, path, directory, null);
    }

    private AgentWorkspaceTreeNode toNode(Path root, Path path, boolean directory, List<AgentWorkspaceTreeNode> children) throws IOException {
        String relative = relative(root, path);
        return AgentWorkspaceTreeNode.builder()
                .name(relative.isEmpty() ? root.getFileName().toString() : path.getFileName().toString())
                .path(relative)
                .type(directory ? "directory" : "file")
                .hasChildren(directory && hasVisibleChild(root, path))
                .size(directory ? null : Files.size(path))
                .lastModified(Files.getLastModifiedTime(path).toMillis())
                .children(children)
                .build();
    }

    private boolean hasVisibleChild(Path root, Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        List<IgnoreRule> ignoreRules = readIgnoreRules(root);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                Path realChild = child.toRealPath();
                if (realChild.startsWith(root) && !ignored(root, realChild, ignoreRules)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    private Path resolveInsideWorkspace(Path root, String rawPath) throws IOException {
        String trimmed = StringUtils.trimToEmpty(rawPath);
        Path candidate = trimmed.isEmpty() ? root : root.resolve(trimmed).normalize().toAbsolutePath();
        Path realPath = candidate.toRealPath();
        if (!realPath.startsWith(root)) {
            throw new IllegalArgumentException("路径越权：" + rawPath);
        }
        return realPath;
    }

    private boolean ignored(Path root, Path path, List<IgnoreRule> ignoreRules) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        for (Path segment : relative) {
            if (DEFAULT_IGNORED_NAMES.contains(segment.toString())) {
                return true;
            }
        }
        boolean directory = Files.isDirectory(path);
        return ignoreRules.stream().anyMatch(rule -> rule.matches(relative, directory));
    }

    private List<IgnoreRule> readIgnoreRules(Path root) {
        Path gitignore = root.resolve(".gitignore");
        if (!Files.isRegularFile(gitignore)) {
            return List.of();
        }
        try {
            Set<String> seen = new HashSet<>();
            List<IgnoreRule> rules = new ArrayList<>();
            for (String line : Files.readAllLines(gitignore, StandardCharsets.UTF_8)) {
                String pattern = StringUtils.trim(line);
                if (StringUtils.isBlank(pattern) || pattern.startsWith("#") || pattern.startsWith("!")) {
                    continue;
                }
                if (seen.add(pattern)) {
                    rules.add(IgnoreRule.parse(pattern));
                }
            }
            return rules;
        } catch (IOException e) {
            return List.of();
        }
    }

    private String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private int limit(Integer value) {
        return value == null ? DEFAULT_LIMIT : value;
    }

    private <T> Response<T> validate(Object request) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return null;
        }
        String message = violations.stream()
                .map(ConstraintViolation::getMessage)
                .distinct()
                .collect(Collectors.joining("; "));
        return illegal(message);
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    private <T> Response<T> illegal(String message) {
        return Response.<T>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info(message)
                .build();
    }

    private record TreeBuildResult(AgentWorkspaceTreeNode node, boolean truncated) {
    }

    private record IgnoreRule(String pattern, boolean directoryOnly, boolean anchored, boolean containsSlash) {

        static IgnoreRule parse(String raw) {
            String pattern = raw;
            boolean directoryOnly = pattern.endsWith("/");
            if (directoryOnly) {
                pattern = pattern.substring(0, pattern.length() - 1);
            }
            boolean anchored = pattern.startsWith("/");
            if (anchored) {
                pattern = pattern.substring(1);
            }
            return new IgnoreRule(pattern, directoryOnly, anchored, pattern.contains("/"));
        }

        boolean matches(Path relativePath, boolean directory) {
            if (directoryOnly && !directory) {
                return false;
            }
            String relative = relativePath.toString().replace('\\', '/');
            if (relative.isEmpty()) {
                return false;
            }
            if (containsSlash || anchored) {
                return globMatches(pattern, relative) || relative.startsWith(pattern + "/");
            }
            for (Path segment : relativePath) {
                if (globMatches(pattern, segment.toString())) {
                    return true;
                }
            }
            return false;
        }

        private boolean globMatches(String pattern, String value) {
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < pattern.length(); i++) {
                char ch = pattern.charAt(i);
                if (ch == '*') {
                    regex.append("[^/]*");
                } else if (ch == '?') {
                    regex.append("[^/]");
                } else {
                    if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(ch);
                }
            }
            return value.matches(regex.toString());
        }
    }

}
