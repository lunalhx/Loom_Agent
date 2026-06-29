package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ApprovalDiff;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

@Component
public class WriteFileTool extends FileSystemToolSupport implements AgentTool {

    private static final String FINGERPRINT_VERSION = "write-v1";

    public WriteFileTool(AgentRuntimeProperties properties) {
        super(properties);
    }

    @Autowired
    public WriteFileTool(AgentRuntimeProperties properties, WorkspacePort workspacePort) {
        super(properties, workspacePort);
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("write_file")
                .description("创建或覆盖工作区内文本文件；写入前必须人工确认")
                .inputSchema("{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{" +
                        "\"path\":{\"type\":\"string\",\"description\":\"相对路径\"}," +
                        "\"content\":{\"type\":\"string\",\"description\":\"文件内容\"}," +
                        "\"mode\":{\"type\":\"string\",\"enum\":[\"create\",\"overwrite\"],\"description\":\"create 或 overwrite\"}" +
                        "}," +
                        "\"required\":[\"path\",\"content\"]" +
                        "}")
                .build();
    }

    @Override
    public ToolPolicyDecision policy(ToolCall call) {
        String path = text(call.getInput(), "path", "");
        String mode = text(call.getInput(), "mode", "create");
        String content = text(call.getInput(), "content", "");

        ToolPolicyDecision decision = ToolPolicyDecision.writeConfirm(
                "文件写入会修改工作区内容，需要人工确认",
                "write_file mode=" + mode + " path=" + path + " chars=" + StringUtils.length(content));
        decision.setDiff(buildWriteDiff(call, mode, content));
        if (decision.getDiff() != null && decision.getDiff().getErrorCode() == null) {
            decision.setPolicyFingerprint(buildWriteFingerprint(call, mode, content));
        }
        return decision;
    }

    private ApprovalDiff buildWriteDiff(ToolCall call, String mode, String content) {
        if (!"create".equals(mode) && !"overwrite".equals(mode)) {
            return null;
        }
        try {
            Path targetPath;
            String oldText;
            if ("create".equals(mode)) {
                targetPath = resolveWritablePath(call, "path", null);
                oldText = "";
            } else {
                targetPath = resolvePath(call, "path", null);
                if (!isAllowedRegularFile(call, targetPath)) {
                    return null;
                }
                oldText = Files.readString(targetPath, StandardCharsets.UTF_8);
            }
            if (exceedsUtf8Limit(content)) {
                return null;
            }
            return StructuredDiffBuilder.oldNew(relative(call, targetPath), oldText, content);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildWriteFingerprint(ToolCall call, String mode, String content) {
        try {
            Path targetPath = "create".equals(mode)
                    ? resolveWritablePath(call, "path", null)
                    : resolvePath(call, "path", null);
            String canonicalPath = relative(call, targetPath);
            StringBuilder source = new StringBuilder(FINGERPRINT_VERSION)
                    .append('\n').append("write_file")
                    .append('\n').append(canonicalPath)
                    .append('\n').append(mode);
            if ("overwrite".equals(mode)) {
                String oldContent = Files.readString(targetPath, StandardCharsets.UTF_8);
                source.append('\n').append(DigestUtils.sha256Hex(oldContent));
            }
            source.append('\n').append(DigestUtils.sha256Hex(content));
            return DigestUtils.sha256Hex(source.toString());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            String mode = text(call.getInput(), "mode", "create");
            String content = text(call.getInput(), "content", "");
            if (!"create".equals(mode) && !"overwrite".equals(mode)) {
                return failure("invalid_mode", "mode 只能是 create 或 overwrite", startedAt);
            }

            // UTF-8 byte check (both approval preview and execution)
            if (exceedsUtf8Limit(content)) {
                return failure("file_too_large", "写入内容超过文件大小上限", startedAt);
            }

            // Approval staleness check
            if (StringUtils.isNotBlank(call.getApprovedPolicyFingerprint())) {
                String currentFingerprint = buildWriteFingerprint(call, mode, content);
                if (currentFingerprint != null && !StringUtils.equals(call.getApprovedPolicyFingerprint(), currentFingerprint)) {
                    return failure("approval_stale", "文件在审批后发生变化，需要重新预览并审批", startedAt);
                }
            }

            Path path;
            if ("create".equals(mode)) {
                path = resolveWritablePath(call, "path", null);
                if (Files.exists(path)) {
                    return failure("file_exists", "文件已存在：" + relative(call, path), startedAt);
                }
                // Create parent directories, then re-verify no symlink escape
                Path parent = path.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                    // Re-resolve to guard against concurrent symlink creation
                    Path realParent = parent.toRealPath();
                    Path root = workspaceRoot(call);
                    if (!realParent.startsWith(root)) {
                        throw new IOException("路径越权：" + relative(call, path));
                    }
                    path = realParent.resolve(path.getFileName()).normalize().toAbsolutePath();
                    if (Files.exists(path)) {
                        return failure("file_exists", "文件已存在：" + relative(call, path), startedAt);
                    }
                }
            } else {
                path = resolvePath(call, "path", null);
                if (!Files.isRegularFile(path)) {
                    return failure("file_not_found", "覆盖模式要求文件已存在：" + relative(call, path), startedAt);
                }
            }

            writeAtomically(path, content, "create".equals(mode));
            return ToolResult.success("written: " + relative(call, path) + "\nmode: " + mode, false, elapsed(startedAt));
        } catch (Exception e) {
            return failure("write_file_failed", e.getMessage(), startedAt);
        }
    }

    private void writeAtomically(Path path, String content, boolean isCreate) throws Exception {
        Set<PosixFilePermission> existingPermissions = null;
        boolean isPosix = false;
        if (!isCreate && Files.exists(path)) {
            try {
                existingPermissions = Files.getPosixFilePermissions(path);
                isPosix = true;
            } catch (UnsupportedOperationException | ClassCastException e) {
                isPosix = false;
            }
        }

        Path temp = Files.createTempFile(path.getParent(), "agent-write-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            if (isCreate) {
                // Use REPLACE_EXISTING for create (after confirming file doesn't exist)
                // but prefer ATOMIC_MOVE when possible
                try {
                    Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (UnsupportedOperationException | IOException e) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                try {
                    Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (UnsupportedOperationException | IOException e) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
                if (isPosix && existingPermissions != null) {
                    try {
                        Files.setPosixFilePermissions(path, existingPermissions);
                    } catch (Exception ignored) {
                        // best-effort permission preservation
                    }
                }
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

}
