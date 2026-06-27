package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DeleteFilesTool extends FileSystemToolSupport implements AgentTool {

    private static final int MAX_FILES = 20;
    private static final Set<String> PROTECTED_NAMES = Set.of(".git", ".idea", "target", "node_modules");

    public DeleteFilesTool(AgentRuntimeProperties properties) {
        super(properties);
    }

    @Autowired
    public DeleteFilesTool(AgentRuntimeProperties properties, WorkspacePort workspacePort) {
        super(properties, workspacePort);
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("delete_files")
                .description("删除工作区内指定文件；每次最多 20 个，仅支持普通文件，不支持目录/通配符/符号链接，需高危审批")
                .inputSchema("{\"paths\":[\"必填，1~20 个不重复工作区相对路径\"]}")
                .build();
    }

    @Override
    public ToolPolicyDecision policy(ToolCall call) {
        try {
            List<String> rawPaths = extractPaths(call);
            ValidationResult validation = validateAll(call, rawPaths);
            if (!validation.valid()) {
                return ToolPolicyDecision.highRiskDeny(validation.error(), String.join(", ", rawPaths));
            }
            long totalSize = validation.paths().stream()
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
            String preview = "删除 " + validation.paths().size() + " 个文件（"
                    + formatSize(totalSize) + "）:\n"
                    + validation.relativePaths().stream()
                    .map(p -> "  " + p)
                    .collect(Collectors.joining("\n"));
            return ToolPolicyDecision.highRiskConfirm("文件删除不可恢复，需要高危审批", preview);
        } catch (Exception e) {
            return ToolPolicyDecision.highRiskDeny("路径校验失败：" + e.getMessage(),
                    call.getInput() == null ? "" : call.getInput().toString());
        }
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            List<String> rawPaths = extractPaths(call);
            ValidationResult validation = validateAll(call, rawPaths);
            if (!validation.valid()) {
                return failure("delete_files_rejected", validation.error(), startedAt);
            }

            List<String> deleted = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (Path path : validation.paths()) {
                try {
                    Files.delete(path);
                    deleted.add(relative(call, path));
                } catch (IOException e) {
                    failed.add(relative(call, path) + " (" + e.getMessage() + ")");
                }
            }

            StringBuilder result = new StringBuilder();
            if (!deleted.isEmpty()) {
                result.append("已删除 ").append(deleted.size()).append(" 个文件:\n");
                deleted.forEach(p -> result.append("  ").append(p).append("\n"));
            }
            if (!failed.isEmpty()) {
                result.append("删除失败 ").append(failed.size()).append(" 个文件:\n");
                failed.forEach(p -> result.append("  ").append(p).append("\n"));
            }
            boolean allSucceeded = failed.isEmpty();
            return ToolResult.success(result.toString().stripTrailing(), allSucceeded, elapsed(startedAt));
        } catch (Exception e) {
            return failure("delete_files_failed", e.getMessage(), startedAt);
        }
    }

    private List<String> extractPaths(ToolCall call) {
        JsonNode input = call.getInput();
        if (input == null || !input.has("paths") || input.get("paths").isNull()) {
            throw new IllegalArgumentException("paths 必填");
        }
        JsonNode pathsNode = input.get("paths");
        if (!pathsNode.isArray()) {
            throw new IllegalArgumentException("paths 必须是数组");
        }
        List<String> rawPaths = new ArrayList<>();
        for (JsonNode node : pathsNode) {
            if (node.isNull() || node.asText().isBlank()) {
                throw new IllegalArgumentException("paths 中的路径不能为空");
            }
            rawPaths.add(node.asText());
        }
        if (rawPaths.isEmpty()) {
            throw new IllegalArgumentException("paths 不能为空数组");
        }
        if (rawPaths.size() > MAX_FILES) {
            throw new IllegalArgumentException("一次最多删除 " + MAX_FILES + " 个文件，收到 " + rawPaths.size() + " 个");
        }
        if (rawPaths.size() != new LinkedHashSet<>(rawPaths).size()) {
            throw new IllegalArgumentException("paths 包含重复路径");
        }
        return rawPaths;
    }

    private ValidationResult validateAll(ToolCall call, List<String> rawPaths) {
        Path root;
        try {
            root = workspaceRoot(call);
        } catch (IOException e) {
            return ValidationResult.invalid("无法解析工作区根目录：" + e.getMessage());
        }

        List<Path> resolved = new ArrayList<>();
        List<String> relativePaths = new ArrayList<>();
        for (String rawPath : rawPaths) {
            if (rawPath.startsWith("/") || rawPath.contains("..")) {
                return ValidationResult.invalid("禁止绝对路径或上级目录：" + rawPath);
            }
            if (containsWildcard(rawPath)) {
                return ValidationResult.invalid("禁止使用通配符：" + rawPath);
            }

            Path candidate;
            try {
                candidate = root.resolve(rawPath).normalize().toAbsolutePath();
            } catch (Exception e) {
                return ValidationResult.invalid("路径解析失败：" + rawPath + " (" + e.getMessage() + ")");
            }

            if (!candidate.startsWith(root)) {
                return ValidationResult.invalid("路径越权：" + rawPath);
            }

            if (candidate.equals(root)) {
                return ValidationResult.invalid("禁止删除工作区根目录");
            }

            if (Files.isSymbolicLink(candidate)) {
                return ValidationResult.invalid("禁止删除符号链接：" + rawPath);
            }

            if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return ValidationResult.invalid("禁止删除目录：" + rawPath);
            }

            if (!Files.exists(candidate)) {
                return ValidationResult.invalid("文件不存在：" + rawPath);
            }

            if (!Files.isRegularFile(candidate)) {
                return ValidationResult.invalid("不是普通文件：" + rawPath);
            }

            try {
                Path realParent = candidate.getParent().toRealPath();
                if (!realParent.startsWith(root)) {
                    return ValidationResult.invalid("路径通过符号链接逃逸工作区：" + rawPath);
                }
            } catch (IOException e) {
                return ValidationResult.invalid("无法解析父目录：" + rawPath + " (" + e.getMessage() + ")");
            }

            if (isProtectedPath(root, candidate)) {
                return ValidationResult.invalid("路径被安全策略保护：" + rawPath);
            }

            resolved.add(candidate);
            relativePaths.add(root.relativize(candidate).toString());
        }
        return ValidationResult.valid(resolved, relativePaths);
    }

    private boolean isProtectedPath(Path root, Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        String normalized = relative.toString().replace('\\', '/');
        if (normalized.equals("docs/env/.env") || normalized.startsWith("docs/env/.env/")) {
            return true;
        }
        for (Path segment : relative) {
            if (PROTECTED_NAMES.contains(segment.toString())) {
                return true;
            }
        }
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".key") || fileName.endsWith(".pem") || fileName.endsWith(".p12");
    }

    private boolean containsWildcard(String path) {
        for (char ch : path.toCharArray()) {
            if (ch == '*' || ch == '?' || ch == '[' || ch == ']' || ch == '{' || ch == '}') {
                return true;
            }
        }
        return false;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private record ValidationResult(boolean valid, String error, List<Path> paths, List<String> relativePaths) {
        static ValidationResult valid(List<Path> paths, List<String> relativePaths) {
            return new ValidationResult(true, null, paths, relativePaths);
        }

        static ValidationResult invalid(String error) {
            return new ValidationResult(false, error, List.of(), List.of());
        }
    }
}
