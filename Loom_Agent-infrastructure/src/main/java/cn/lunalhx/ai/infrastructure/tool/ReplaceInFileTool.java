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
public class ReplaceInFileTool extends FileSystemToolSupport implements AgentTool {

    private static final String FINGERPRINT_VERSION = "replace-v1";

    public ReplaceInFileTool(AgentRuntimeProperties properties) {
        super(properties);
    }

    @Autowired
    public ReplaceInFileTool(AgentRuntimeProperties properties, WorkspacePort workspacePort) {
        super(properties, workspacePort);
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("replace_in_file")
                .description("精确替换工作区内文本文件的一段内容。何时使用：局部修改已知文件时。何时不要使用：创建新文件或整体重写请用 write_file。oldText 必须精确匹配（含空白），expectedOccurrences 指定期望匹配次数。权限：写入前必须人工确认")
                .inputSchema("{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{" +
                        "\"path\":{\"type\":\"string\",\"minLength\":1,\"description\":\"相对路径\"}," +
                        "\"oldText\":{\"type\":\"string\",\"minLength\":1,\"description\":\"要替换的旧文本\"}," +
                        "\"newText\":{\"type\":\"string\",\"description\":\"替换后的新文本\"}," +
                        "\"expectedOccurrences\":{\"type\":\"integer\",\"minimum\":1,\"default\":1,\"description\":\"期望匹配次数\"}" +
                        "}," +
                        "\"required\":[\"path\",\"oldText\",\"newText\"]," +
                        "\"additionalProperties\":false" +
                        "}")
                .build();
    }

    @Override
    public ToolPolicyDecision policy(ToolCall call) {
        String path = text(call.getInput(), "path", "");
        String oldText = text(call.getInput(), "oldText", "");
        String newText = text(call.getInput(), "newText", "");
        int expectedOccurrences = Math.max(1, integer(call.getInput(), "expectedOccurrences", 1));

        ToolPolicyDecision decision = ToolPolicyDecision.writeConfirm(
                "文件替换会修改工作区内容，需要人工确认",
                "replace_in_file path=" + path
                        + " oldChars=" + StringUtils.length(oldText)
                        + " newChars=" + StringUtils.length(newText));
        decision.setDiff(buildReplaceDiff(call, path, oldText, newText, expectedOccurrences));
        if (decision.getDiff() != null && decision.getDiff().getErrorCode() == null) {
            decision.setPolicyFingerprint(buildReplaceFingerprint(call, oldText, newText, expectedOccurrences));
        }
        return decision;
    }

    private ApprovalDiff buildReplaceDiff(ToolCall call, String path, String oldText, String newText, int expectedOccurrences) {
        if (oldText.isEmpty()) {
            return null;
        }
        try {
            Path filePath = resolvePath(call, "path", null);
            if (!isAllowedRegularFile(call, filePath)) {
                return null;
            }
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            int occurrences = countOccurrences(content, oldText);
            if (occurrences != expectedOccurrences) {
                return null;
            }
            String replaced = content.replace(oldText, newText);
            if (exceedsUtf8Limit(replaced)) {
                return null;
            }
            return StructuredDiffBuilder.oldNew(relative(call, filePath), content, replaced);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildReplaceFingerprint(ToolCall call, String oldText, String newText, int expectedOccurrences) {
        try {
            Path filePath = resolvePath(call, "path", null);
            String canonicalPath = relative(call, filePath);
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            String replaced = content.replace(oldText, newText);
            return DigestUtils.sha256Hex(
                    FINGERPRINT_VERSION + "\n" +
                    "replace_in_file" + "\n" +
                    canonicalPath + "\n" +
                    DigestUtils.sha256Hex(content) + "\n" +
                    DigestUtils.sha256Hex(replaced) + "\n" +
                    expectedOccurrences);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            Path path = resolvePath(call, "path", null);
            if (!isAllowedRegularFile(call, path)) {
                return failure("file_not_allowed", "文件不存在、过大或被禁止：" + relative(call, path), startedAt);
            }
            String oldText = text(call.getInput(), "oldText", "");
            String newText = text(call.getInput(), "newText", "");
            int expectedOccurrences = Math.max(1, integer(call.getInput(), "expectedOccurrences", 1));
            if (oldText.isEmpty()) {
                return failure("old_text_required", "oldText 不能为空", startedAt);
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);
            int occurrences = countOccurrences(content, oldText);
            if (occurrences != expectedOccurrences) {
                return failure("occurrence_mismatch", "匹配次数为 " + occurrences + "，期望 " + expectedOccurrences, startedAt);
            }

            String replaced = content.replace(oldText, newText);

            // UTF-8 byte check on result
            if (exceedsUtf8Limit(replaced)) {
                return failure("file_too_large", "替换后文件内容超过大小上限", startedAt);
            }

            // Approval staleness check
            if (StringUtils.isNotBlank(call.getApprovedPolicyFingerprint())) {
                String currentFingerprint = buildReplaceFingerprint(call, oldText, newText, expectedOccurrences);
                if (currentFingerprint != null && !StringUtils.equals(call.getApprovedPolicyFingerprint(), currentFingerprint)) {
                    return failure("approval_stale", "文件在审批后发生变化，需要重新预览并审批", startedAt);
                }
            }

            writeAtomically(path, replaced);
            return ToolResult.success("updated: " + relative(call, path) + "\nreplacements: " + occurrences, false, elapsed(startedAt));
        } catch (Exception e) {
            return failure("replace_in_file_failed", e.getMessage(), startedAt);
        }
    }

    private int countOccurrences(String content, String oldText) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(oldText, index)) >= 0) {
            count++;
            index += oldText.length();
        }
        return count;
    }

    private void writeAtomically(Path path, String content) throws Exception {
        Set<PosixFilePermission> existingPermissions = null;
        boolean isPosix = false;
        try {
            existingPermissions = Files.getPosixFilePermissions(path);
            isPosix = true;
        } catch (UnsupportedOperationException | ClassCastException e) {
            isPosix = false;
        }

        Path temp = Files.createTempFile(path.getParent(), "agent-replace-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
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
        } finally {
            Files.deleteIfExists(temp);
        }
    }

}
