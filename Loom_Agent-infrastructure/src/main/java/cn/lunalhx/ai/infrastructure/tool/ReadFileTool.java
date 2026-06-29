package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class ReadFileTool extends FileSystemToolSupport implements AgentTool {

    private static final int MAX_LINES = 200;

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
                .inputSchema("{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{" +
                        "\"path\":{\"type\":\"string\",\"description\":\"相对路径\"}," +
                        "\"startLine\":{\"type\":\"integer\",\"minimum\":1,\"description\":\"起始行号，默认 1\"}," +
                        "\"endLine\":{\"type\":\"integer\",\"minimum\":1,\"description\":\"结束行号，默认 startLine+119，最多 200 行\"}" +
                        "}," +
                        "\"required\":[\"path\"]" +
                        "}")
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
            endLine = Math.max(startLine, Math.min(startLine + MAX_LINES - 1, endLine));

            StringBuilder output = new StringBuilder();
            int totalLines = 0;
            int shownLines = 0;

            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                int currentLine = 0;
                while ((line = reader.readLine()) != null) {
                    currentLine++;
                    totalLines = currentLine;
                    if (currentLine >= startLine && currentLine <= endLine) {
                        output.append(currentLine).append(": ").append(line).append('\n');
                        shownLines++;
                    }
                    // Stop reading once we've passed the requested range
                    if (currentLine > endLine) {
                        // But still count remaining lines for totalLines
                        while (reader.readLine() != null) {
                            totalLines++;
                        }
                        break;
                    }
                }
            }

            boolean hasMore = endLine < totalLines;
            boolean truncated = shownLines < totalLines;

            // Append machine-readable footer
            output.append("\n---\n");
            output.append("shownLines: ").append(shownLines).append('\n');
            output.append("totalLines: ").append(totalLines).append('\n');
            if (hasMore) {
                output.append("nextStartLine: ").append(endLine + 1).append('\n');
            }

            return ToolResult.success(output.toString(), truncated, elapsed(startedAt));
        } catch (Exception e) {
            return failure("read_file_failed", e.getMessage(), startedAt);
        }
    }

}
