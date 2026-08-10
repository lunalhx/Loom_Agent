package cn.lunalhx.ai.infrastructure.mcp;

import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ApprovalRequirement;
import cn.lunalhx.ai.domain.tool.model.CallEffectAssessment;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

/**
 * Adapter from a remote MCP tool to the loom {@link AgentTool} contract.
 *
 * <p>The exposed name is {@code <server>_<tool>} (non {@code [a-zA-Z0-9_-]}
 * characters replaced by {@code _}), identical to the naming used by
 * {@code McpToolUtils.prefixedToolName} so the model-visible name always
 * matches the registry key. MCP effect/permission mapping is intentionally
 * incomplete in this ticket, so MCP tools are unavailable in Plan Mode.
 *
 * <p>This class only forwards one call to the MCP client; governance
 * (allowlist, schema, approval, sanitization) stays in the shared tool chain.
 */
public class McpAgentTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String NAMING_PATTERN = "[^a-zA-Z0-9_-]";

    private final McpSyncClient client;
    private final String serverName;
    private final McpSchema.Tool tool;
    private final ApprovalRequirement approvalRequirement;

    public McpAgentTool(McpSyncClient client, String serverName, McpSchema.Tool tool,
                        ApprovalRequirement approvalRequirement) {
        this.client = client;
        this.serverName = serverName;
        this.tool = tool;
        this.approvalRequirement = approvalRequirement == null
                ? ApprovalRequirement.SESSION_POLICY : approvalRequirement;
    }

    public static String prefixedName(String serverName, String toolName) {
        String prefix = serverName.replaceAll(NAMING_PATTERN, "_");
        String name = toolName.replaceAll(NAMING_PATTERN, "_");
        if (prefix.isEmpty() || name.isEmpty()) {
            throw new IllegalArgumentException("MCP server/tool name must not be empty after sanitization");
        }
        return prefix + "_" + name;
    }

    public String serverName() {
        return serverName;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name(prefixedName(serverName, tool.name()))
                .description(tool.description() == null ? "" : tool.description())
                .inputSchema(toJsonSchema(tool.inputSchema()))
                .capabilityEnvelope(ToolCapabilityEnvelope.untrustedUnknown())
                .approvalRequirement(approvalRequirement)
                .build();
    }

    @Override
    public CallEffectAssessment assessEffect(ToolCall call) {
        return CallEffectAssessment.untrusted();
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> arguments = arguments(call);
        try {
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(tool.name(), arguments));
            String observation = render(result.content());
            if (Boolean.TRUE.equals(result.isError())) {
                return ToolResult.failure("mcp_tool_error", observation, elapsed(startedAt));
            }
            return ToolResult.success(observation, false, elapsed(startedAt));
        } catch (Exception e) {
            return ToolResult.failure("mcp_call_failed", String.valueOf(e.getMessage()), elapsed(startedAt));
        }
    }

    /** MCP inputSchema is already JSON Schema; serialize the record as-is. */
    private String toJsonSchema(McpSchema.JsonSchema schema) {
        if (schema == null) {
            return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
        }
        try {
            return MAPPER.writeValueAsString(schema);
        } catch (Exception e) {
            return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
        }
    }

    private Map<String, Object> arguments(ToolCall call) {
        JsonNode input = call.getInput();
        if (input == null || input.isMissingNode() || input.isNull() || !input.isObject()) {
            return Map.of();
        }
        return MAPPER.convertValue(input, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
        });
    }

    private String render(java.util.List<McpSchema.Content> content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content item : content) {
            if (item instanceof McpSchema.TextContent text) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(text.text());
            } else if (item != null) {
                // non-text content (images/audio/resources): keep a short marker
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append('[').append(item.type()).append("]");
            }
        }
        return sb.toString();
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
