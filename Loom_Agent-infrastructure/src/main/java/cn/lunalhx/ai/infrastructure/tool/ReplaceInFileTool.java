package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
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
public class ReplaceInFileTool extends FileSystemToolSupport implements AgentTool {

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
                .description("替换工作区内文本文件的一段内容；写入前必须人工确认")
                .inputSchema("{\"path\":\"必填相对路径\",\"oldText\":\"必填旧文本\",\"newText\":\"必填新文本\",\"expectedOccurrences\":\"默认 1\"}")
                .build();
    }

    @Override
    public ToolPolicyDecision policy(ToolCall call) {
        String path = text(call.getInput(), "path", "");
        return ToolPolicyDecision.writeConfirm(
                "文件替换会修改工作区内容，需要人工确认",
                "replace_in_file path=" + path
                        + " oldChars=" + StringUtils.length(text(call.getInput(), "oldText", ""))
                        + " newChars=" + StringUtils.length(text(call.getInput(), "newText", "")));
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

            writeAtomically(path, content.replace(oldText, newText));
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
        Path temp = Files.createTempFile(path.getParent(), "agent-replace-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

}
