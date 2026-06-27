package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class DeleteFilesTool extends FileSystemToolSupport implements AgentTool {

    private static final int MAX_TARGETS = 20;
    private static final int MAX_PREVIEW_PATHS = 50;
    private static final int LARGE_DELETE_ENTRIES = 100;
    private static final long LARGE_DELETE_BYTES = 10L * 1024 * 1024;
    private static final String FINGERPRINT_VERSION = "delete-manifest-v1";

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
                .description("删除工作区内明确指定的文件或目录；目录会在高危审批后递归删除，每次最多 20 个目标，不支持通配符")
                .inputSchema("{\"paths\":[\"必填，1~20 个工作区相对文件或目录路径\"]}")
                .build();
    }

    @Override
    public ToolPolicyDecision policy(ToolCall call) {
        try {
            DeletionManifest manifest = buildManifest(call);
            ToolPolicyDecision decision = ToolPolicyDecision.highRiskConfirm(
                    "文件或目录删除不可恢复，需要高危审批",
                    buildPreview(manifest));
            decision.setPolicyFingerprint(manifest.fingerprint());
            decision.setMetadata(Map.of("deletePreview", manifest.metadata()));
            return decision;
        } catch (DeleteValidationException e) {
            return ToolPolicyDecision.highRiskDeny(e.getMessage(), inputPreview(call));
        } catch (Exception e) {
            return ToolPolicyDecision.highRiskDeny("路径校验失败：" + e.getMessage(), inputPreview(call));
        }
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            DeletionManifest manifest = buildManifest(call);
            if (StringUtils.isNotBlank(call.getApprovedPolicyFingerprint())
                    && !StringUtils.equals(call.getApprovedPolicyFingerprint(), manifest.fingerprint())) {
                return failure("approval_stale", "删除目标在审批后发生变化，需要重新预览并审批", startedAt);
            }

            List<DeletionEntry> deleteOrder = manifest.entries().stream()
                    .sorted(Comparator.comparingInt((DeletionEntry entry) -> entry.path().getNameCount())
                            .reversed()
                            .thenComparing(DeletionEntry::relative, Comparator.reverseOrder()))
                    .toList();
            int deleted = 0;
            for (DeletionEntry entry : deleteOrder) {
                try {
                    validateEntryStillSafe(entry);
                    Files.delete(entry.path());
                    deleted++;
                } catch (IOException | DeleteValidationException e) {
                    return failure(
                            "delete_files_partial_failure",
                            "已删除 " + deleted + " 个清单项，删除 " + entry.relative() + " 失败：" + e.getMessage(),
                            startedAt);
                }
            }

            return ToolResult.success(
                    "已删除 " + manifest.relativeTargets().size() + " 个目标（"
                            + String.join("、", manifest.relativeTargets()) + "），共 "
                            + manifest.fileCount() + " 个文件、"
                            + manifest.directoryCount() + " 个目录、"
                            + manifest.symlinkCount() + " 个符号链接",
                    false,
                    elapsed(startedAt));
        } catch (DeleteValidationException e) {
            return failure("delete_files_rejected", e.getMessage(), startedAt);
        } catch (Exception e) {
            return failure("delete_files_failed", e.getMessage(), startedAt);
        }
    }

    private DeletionManifest buildManifest(ToolCall call) throws IOException {
        Path root = workspaceRoot(call).toRealPath();
        List<String> rawPaths = extractPaths(call);
        List<Path> targets = normalizeTargets(root, rawPaths);
        List<DeletionEntry> entries = new ArrayList<>();

        for (Path target : targets) {
            collectEntries(root, target, entries);
        }
        entries.sort(Comparator.comparing(DeletionEntry::relative));

        long fileCount = entries.stream().filter(entry -> entry.kind() == EntryKind.FILE).count();
        long directoryCount = entries.stream().filter(entry -> entry.kind() == EntryKind.DIRECTORY).count();
        long symlinkCount = entries.stream().filter(entry -> entry.kind() == EntryKind.SYMLINK).count();
        long totalBytes = entries.stream().mapToLong(DeletionEntry::size).sum();
        List<String> relativeTargets = targets.stream().map(path -> relative(root, path)).toList();
        Set<String> riskFlags = riskFlags(entries, targets, totalBytes);

        List<Map<String, Object>> targetMetadata = new ArrayList<>();
        for (Path target : targets) {
            EntryKind kind = entryKind(target);
            targetMetadata.add(Map.of("path", relative(root, target), "kind", kind.name()));
        }
        List<String> samplePaths = entries.stream()
                .map(DeletionEntry::relative)
                .limit(MAX_PREVIEW_PATHS)
                .toList();
        boolean truncated = entries.size() > MAX_PREVIEW_PATHS;
        boolean secondConfirmation = riskFlags.contains("RECURSIVE")
                || riskFlags.contains("MULTIPLE_TARGETS")
                || riskFlags.contains("SECRET_LIKE")
                || riskFlags.contains("LARGE_DELETE");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("targetCount", targets.size());
        metadata.put("fileCount", fileCount);
        metadata.put("directoryCount", directoryCount);
        metadata.put("symlinkCount", symlinkCount);
        metadata.put("totalBytes", totalBytes);
        metadata.put("targets", targetMetadata);
        metadata.put("samplePaths", samplePaths);
        metadata.put("truncated", truncated);
        metadata.put("riskFlags", new ArrayList<>(riskFlags));
        metadata.put("requiresSecondConfirmation", secondConfirmation);

        String fingerprint = fingerprint(root, relativeTargets, entries);
        return new DeletionManifest(
                targets,
                relativeTargets,
                entries,
                fileCount,
                directoryCount,
                symlinkCount,
                totalBytes,
                fingerprint,
                metadata);
    }

    private List<String> extractPaths(ToolCall call) {
        JsonNode input = call.getInput();
        if (input == null || !input.has("paths") || input.get("paths").isNull()) {
            throw new DeleteValidationException("paths 必填");
        }
        JsonNode pathsNode = input.get("paths");
        if (!pathsNode.isArray()) {
            throw new DeleteValidationException("paths 必须是数组");
        }

        List<String> rawPaths = new ArrayList<>();
        for (JsonNode node : pathsNode) {
            if (!node.isTextual() || node.asText().isBlank()) {
                throw new DeleteValidationException("paths 中的路径必须是非空字符串");
            }
            rawPaths.add(node.asText().trim());
        }
        if (rawPaths.isEmpty()) {
            throw new DeleteValidationException("paths 不能为空数组");
        }
        if (rawPaths.size() > MAX_TARGETS) {
            throw new DeleteValidationException("一次最多删除 " + MAX_TARGETS + " 个目标，收到 " + rawPaths.size() + " 个");
        }
        return rawPaths;
    }

    private List<Path> normalizeTargets(Path root, List<String> rawPaths) throws IOException {
        LinkedHashSet<Path> unique = new LinkedHashSet<>();
        for (String rawPath : rawPaths) {
            Path raw = parseRelativePath(rawPath);
            Path candidate = root.resolve(raw).normalize().toAbsolutePath();
            if (!candidate.startsWith(root)) {
                throw new DeleteValidationException("路径越权：" + rawPath);
            }
            if (candidate.equals(root)) {
                throw new DeleteValidationException("禁止删除工作区根目录");
            }
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new DeleteValidationException("路径不存在：" + rawPath);
            }
            Path parent = candidate.getParent();
            if (parent == null || !parent.toRealPath().startsWith(root)) {
                throw new DeleteValidationException("路径通过符号链接逃逸工作区：" + rawPath);
            }
            rejectGitPath(root, candidate);
            entryKind(candidate);
            unique.add(candidate);
        }

        List<Path> sorted = unique.stream()
                .sorted(Comparator.comparingInt(Path::getNameCount).thenComparing(Path::toString))
                .toList();
        List<Path> effective = new ArrayList<>();
        for (Path candidate : sorted) {
            boolean covered = effective.stream().anyMatch(parent ->
                    Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) && candidate.startsWith(parent));
            if (!covered) {
                effective.add(candidate);
            }
        }
        return effective;
    }

    private Path parseRelativePath(String rawPath) {
        if (containsWildcard(rawPath)) {
            throw new DeleteValidationException("禁止使用通配符：" + rawPath);
        }
        if (rawPath.matches("^[A-Za-z]:[\\\\/].*")) {
            throw new DeleteValidationException("禁止绝对路径：" + rawPath);
        }
        final Path raw;
        try {
            raw = Path.of(rawPath);
        } catch (Exception e) {
            throw new DeleteValidationException("路径解析失败：" + rawPath);
        }
        if (raw.isAbsolute()) {
            throw new DeleteValidationException("禁止绝对路径：" + rawPath);
        }
        for (Path segment : raw) {
            if ("..".equals(segment.toString())) {
                throw new DeleteValidationException("禁止上级目录：" + rawPath);
            }
        }
        return raw;
    }

    private void collectEntries(Path root, Path target, List<DeletionEntry> entries) throws IOException {
        Files.walkFileTree(target, new FileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                rejectGitPath(root, dir);
                entries.add(new DeletionEntry(dir, relative(root, dir), EntryKind.DIRECTORY, 0L));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                rejectGitPath(root, file);
                EntryKind kind;
                if (attrs.isSymbolicLink()) {
                    kind = EntryKind.SYMLINK;
                } else if (attrs.isRegularFile()) {
                    kind = EntryKind.FILE;
                } else {
                    throw new DeleteValidationException("禁止删除特殊文件：" + relative(root, file));
                }
                entries.add(new DeletionEntry(file, relative(root, file), kind, kind == EntryKind.FILE ? attrs.size() : 0L));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                throw new IOException("无法扫描 " + relative(root, file) + "：" + exc.getMessage(), exc);
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void validateEntryStillSafe(DeletionEntry entry) {
        if (!Files.exists(entry.path(), LinkOption.NOFOLLOW_LINKS)) {
            throw new DeleteValidationException("目标在执行前已不存在");
        }
        if (entryKind(entry.path()) != entry.kind()) {
            throw new DeleteValidationException("目标类型在审批后发生变化");
        }
    }

    private EntryKind entryKind(Path path) {
        if (Files.isSymbolicLink(path)) {
            return EntryKind.SYMLINK;
        }
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return EntryKind.FILE;
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return EntryKind.DIRECTORY;
        }
        throw new DeleteValidationException("禁止删除特殊文件：" + path.getFileName());
    }

    private void rejectGitPath(Path root, Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        for (Path segment : relative) {
            if (".git".equalsIgnoreCase(segment.toString())) {
                throw new DeleteValidationException("禁止删除 .git：" + relative(root, path));
            }
        }
    }

    private Set<String> riskFlags(List<DeletionEntry> entries, List<Path> targets, long totalBytes) {
        Set<String> flags = new LinkedHashSet<>();
        if (targets.stream().anyMatch(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))) {
            flags.add("RECURSIVE");
        }
        if (targets.size() > 1) {
            flags.add("MULTIPLE_TARGETS");
        }
        if (entries.stream().anyMatch(entry -> isSecretLike(entry.relative()))) {
            flags.add("SECRET_LIKE");
        }
        if (entries.size() > LARGE_DELETE_ENTRIES || totalBytes > LARGE_DELETE_BYTES) {
            flags.add("LARGE_DELETE");
        }
        AgentRuntimeProperties.UndoProperties undo = properties.getUndo();
        boolean exceedsUndo = undo != null && (entries.size() > undo.getMaxChangedFiles()
                || totalBytes > undo.getMaxChangedBytes());
        boolean potentiallyIgnored = entries.stream().anyMatch(entry -> isPotentiallyIgnored(entry.relative()));
        if (exceedsUndo || potentiallyIgnored) {
            flags.add("UNDO_MAY_BE_UNAVAILABLE");
        }
        return flags;
    }

    private boolean isSecretLike(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        return ".env".equals(fileName)
                || fileName.startsWith(".env.")
                || fileName.endsWith(".key")
                || fileName.endsWith(".pem")
                || fileName.endsWith(".p12");
    }

    private boolean isPotentiallyIgnored(String path) {
        String normalized = "/" + path.replace('\\', '/').toLowerCase(Locale.ROOT) + "/";
        return normalized.contains("/node_modules/")
                || normalized.contains("/target/")
                || normalized.contains("/build/")
                || normalized.contains("/dist/")
                || isSecretLike(path);
    }

    private String fingerprint(Path root, List<String> targets, List<DeletionEntry> entries) {
        StringBuilder source = new StringBuilder(FINGERPRINT_VERSION)
                .append('\n').append(root);
        targets.forEach(target -> source.append("\ntarget:").append(target));
        entries.forEach(entry -> source.append("\nentry:")
                .append(entry.kind()).append(':').append(entry.relative()));
        return DigestUtils.sha256Hex(source.toString());
    }

    private String buildPreview(DeletionManifest manifest) {
        String action = manifest.directoryCount() > 0 ? "将递归删除 " : "将删除 ";
        StringBuilder preview = new StringBuilder(action)
                .append(String.join("、", manifest.relativeTargets()))
                .append("\n共 ")
                .append(manifest.fileCount()).append(" 个文件、")
                .append(manifest.directoryCount()).append(" 个目录、")
                .append(manifest.symlinkCount()).append(" 个符号链接，约 ")
                .append(formatSize(manifest.totalBytes()));
        if (manifest.symlinkCount() > 0) {
            preview.append("\n符号链接只删除链接本身，不会访问链接目标");
        }
        if (((List<?>) manifest.metadata().get("riskFlags")).contains("UNDO_MAY_BE_UNAVAILABLE")) {
            preview.append("\n可能包含 Git 忽略内容或超过快照限制，一键撤销不保证恢复全部内容");
        }
        return preview.toString();
    }

    private String inputPreview(ToolCall call) {
        return call == null || call.getInput() == null ? "" : call.getInput().toString();
    }

    private boolean containsWildcard(String path) {
        for (char ch : path.toCharArray()) {
            if (ch == '*' || ch == '?' || ch == '[' || ch == ']' || ch == '{' || ch == '}') {
                return true;
            }
        }
        return false;
    }

    private String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private enum EntryKind {
        FILE,
        DIRECTORY,
        SYMLINK
    }

    private record DeletionEntry(Path path, String relative, EntryKind kind, long size) {
    }

    private record DeletionManifest(
            List<Path> targets,
            List<String> relativeTargets,
            List<DeletionEntry> entries,
            long fileCount,
            long directoryCount,
            long symlinkCount,
            long totalBytes,
            String fingerprint,
            Map<String, Object> metadata) {
    }

    private static class DeleteValidationException extends RuntimeException {
        DeleteValidationException(String message) {
            super(message);
        }
    }
}
