package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class ReadFileTool extends FileSystemToolSupport implements AgentTool {

    public ReadFileTool(AgentRuntimeProperties properties) {
        super(properties);
    }

    @Autowired
    public ReadFileTool(AgentRuntimeProperties properties, WorkspacePort workspacePort) {
        super(properties, workspacePort);
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("read_file")
                .description("读取工作区内单个文本文件的指定行号范围")
                .inputSchema("{\"path\":\"必填相对路径\",\"startLine\":\"默认 1\",\"endLine\":\"默认 startLine+119，最多 200 行\"}")
                .build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            Path path = resolvePath(call, "path", null);
            if (!isAllowedRegularFile(call, path)) {
                return failure("file_not_allowed", "文件不存在、过大或被禁止：" + relative(call, path), startedAt);
            }
            int startLine = Math.max(1, integer(call.getInput(), "startLine", 1));
            int endLine = integer(call.getInput(), "endLine", startLine + 119);
            endLine = Math.max(startLine, Math.min(startLine + 199, endLine));
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            StringBuilder output = new StringBuilder();
            for (int i = startLine; i <= endLine && i <= lines.size(); i++) {
                output.append(i).append(": ").append(lines.get(i - 1)).append('\n');
            }
            boolean truncated = endLine < lines.size();
            return ToolResult.success(output.toString(), truncated, elapsed(startedAt));
        } catch (Exception e) {
            return failure("read_file_failed", e.getMessage(), startedAt);
        }
    }

}
