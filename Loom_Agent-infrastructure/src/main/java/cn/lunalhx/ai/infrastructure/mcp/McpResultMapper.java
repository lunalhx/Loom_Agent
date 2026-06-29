package cn.lunalhx.ai.infrastructure.mcp;

import cn.lunalhx.ai.domain.tool.model.ToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;

public class McpResultMapper {

    private static final Logger log = LoggerFactory.getLogger(McpResultMapper.class);

    private static final String ERROR_EMPTY = "[MCP tool returned empty error result]";

    private final int maxResultChars;

    public McpResultMapper(int maxResultChars) {
        this.maxResultChars = maxResultChars > 0 ? maxResultChars : 100000;
    }

    public ToolResult map(McpSchema.CallToolResult mcpResult, long elapsedMs) {
        if (mcpResult == null) {
            return ToolResult.failure("mcp_call_failed", "MCP call returned null result", elapsedMs);
        }

        StringBuilder sb = new StringBuilder();
        boolean truncated = false;

        List<McpSchema.Content> content = mcpResult.content();
        if (content != null) {
            for (McpSchema.Content item : content) {
                if (sb.length() >= maxResultChars) {
                    truncated = true;
                    break;
                }
                appendContent(sb, item);
            }
        }

        if (mcpResult.structuredContent() != null) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("[structured_content]\n");
            appendTruncatable(sb, String.valueOf(mcpResult.structuredContent()));
        }

        if (Boolean.TRUE.equals(mcpResult.isError())) {
            if (sb.isEmpty()) {
                sb.append(ERROR_EMPTY);
            }
            return ToolResult.builder()
                    .success(false)
                    .observation(sb.toString())
                    .truncated(truncated)
                    .errorCode("mcp_remote_error")
                    .message("MCP tool returned isError=true")
                    .elapsedMs(elapsedMs)
                    .build();
        }

        if (sb.length() > maxResultChars) {
            sb.setLength(maxResultChars);
            truncated = true;
        }

        return ToolResult.success(sb.toString(), truncated, elapsedMs);
    }

    private void appendContent(StringBuilder sb, McpSchema.Content item) {
        switch (item) {
            case McpSchema.TextContent tc -> {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                appendTruncatable(sb, tc.text());
            }
            case McpSchema.ImageContent ic -> {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("[image mime=").append(safe(ic.mimeType())).append(']');
                if (ic.data() != null) {
                    try {
                        byte[] bytes = Base64.getDecoder().decode(ic.data());
                        sb.append(" size≈").append(bytes.length).append(" bytes");
                    } catch (IllegalArgumentException e) {
                        sb.append(" (non-base64 data)");
                    }
                }
            }
            case McpSchema.AudioContent ac -> {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("[audio mime=").append(safe(ac.mimeType())).append(']');
                if (ac.data() != null) {
                    try {
                        byte[] bytes = Base64.getDecoder().decode(ac.data());
                        sb.append(" size≈").append(bytes.length).append(" bytes");
                    } catch (IllegalArgumentException e) {
                        sb.append(" (non-base64 data)");
                    }
                }
            }
            case McpSchema.EmbeddedResource er -> {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                McpSchema.ResourceContents resource = er.resource();
                if (resource instanceof McpSchema.TextResourceContents trc) {
                    sb.append("[embedded text resource uri=").append(safe(trc.uri())).append("]\n");
                    appendTruncatable(sb, trc.text());
                } else if (resource instanceof McpSchema.BlobResourceContents brc) {
                    sb.append("[embedded blob resource uri=").append(safe(brc.uri()))
                            .append(" mime=").append(safe(brc.mimeType()))
                            .append(']');
                    if (brc.blob() != null) {
                        sb.append(" size≈").append(brc.blob().length()).append(" bytes");
                    }
                } else {
                    sb.append("[embedded resource]");
                }
            }
            case McpSchema.ResourceLink rl -> {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("[resource_link uri=").append(safe(rl.uri()))
                        .append(" name=").append(safe(rl.name()));
                if (rl.mimeType() != null) {
                    sb.append(" mime=").append(safe(rl.mimeType()));
                }
                if (rl.size() != null) {
                    sb.append(" size=").append(rl.size());
                }
                sb.append(']');
            }
            default -> {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("[content type=").append(item.type()).append(']');
            }
        }
    }

    private void appendTruncatable(StringBuilder sb, String text) {
        if (text == null) {
            return;
        }
        int remaining = maxResultChars - sb.length();
        if (remaining <= 0) {
            return;
        }
        if (text.length() <= remaining) {
            sb.append(text);
        } else {
            sb.append(text, 0, remaining);
        }
    }

    private static String safe(String value) {
        return value != null ? value : "unknown";
    }
}
