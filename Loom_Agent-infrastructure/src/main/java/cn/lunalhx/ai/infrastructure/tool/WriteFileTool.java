package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ApprovalDiff;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
public class WriteFileTool extends FileSystemToolSupport implements AgentTool {

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
                .inputSchema("{\"path\":\"必填相对路径\",\"content\":\"必填文件内容\",\"mode\":\"create 或 overwrite\"}")
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
            if (content.length() > properties.getFileMaxBytes()) {
                return null;
            }
            return ApprovalDiff.builder()
                    .format("OLD_NEW")
                    .path(relative(call, targetPath))
                    .oldText(oldText)
                    .newText(content)
                    .editable(false)
                    .build();
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
            Path path = "create".equals(mode)
                    ? resolveWritablePath(call, "path", null)
                    : resolvePath(call, "path", null);
            if ("create".equals(mode) && Files.exists(path)) {
                return failure("file_exists", "文件已存在：" + relative(call, path), startedAt);
            }
            if ("overwrite".equals(mode) && !Files.isRegularFile(path)) {
                return failure("file_not_found", "覆盖模式要求文件已存在：" + relative(call, path), startedAt);
            }
            if (StringUtils.length(content) > properties.getFileMaxBytes()) {
                return failure("file_too_large", "写入内容超过文件大小上限", startedAt);
            }

            Files.createDirectories(path.getParent());
            writeAtomically(path, content);
            return ToolResult.success("written: " + relative(call, path) + "\nmode: " + mode, false, elapsed(startedAt));
        } catch (Exception e) {
            return failure("write_file_failed", e.getMessage(), startedAt);
        }
    }

    private void writeAtomically(Path path, String content) throws Exception {
        Path temp = Files.createTempFile(path.getParent(), "agent-write-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

}
