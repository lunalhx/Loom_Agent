package cn.lunalhx.ai.infrastructure.mcp;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

public class McpAgentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(McpAgentTool.class);

    private final String serverAlias;
    private final String remoteToolName;
    private final String localName;
    private final McpSyncClient client;
    private final McpResultMapper resultMapper;
    private final McpJsonMapper jsonMapper;
    private final ToolPermissionLevel permissionLevel;
    private final McpSchema.Tool remoteToolDef;
    private final int maxDescriptionChars;
    private final int maxSchemaChars;
    private final AgentMetrics metrics;

    public McpAgentTool(String serverAlias,
                        String remoteToolName,
                        String localName,
                        McpSyncClient client,
                        McpResultMapper resultMapper,
                        McpJsonMapper jsonMapper,
                        McpSchema.Tool remoteToolDef,
                        ToolPermissionLevel permissionLevel,
                        int maxDescriptionChars,
                        int maxSchemaChars,
                        AgentMetrics metrics) {
        this.serverAlias = serverAlias;
        this.remoteToolName = remoteToolName;
        this.localName = localName;
        this.client = client;
        this.resultMapper = resultMapper;
        this.jsonMapper = jsonMapper;
        this.remoteToolDef = remoteToolDef;
        this.permissionLevel = permissionLevel;
        this.maxDescriptionChars = maxDescriptionChars;
        this.maxSchemaChars = maxSchemaChars;
        this.metrics = metrics;
    }

    @Override
    public ToolSpec spec() {
        String description = buildDescription();
        String inputSchema = buildInputSchema();
        return ToolSpec.builder()
                .name(localName)
                .description(description)
                .inputSchema(inputSchema)
                .build();
    }

    @Override
    public ToolPolicyDecision policy(ToolCall call) {
        boolean isReadOnly = permissionLevel == ToolPermissionLevel.READ_ONLY;

        ToolPolicyDecision decision = ToolPolicyDecision.builder()
                .permissionLevel(permissionLevel)
                .riskReason("MCP tool " + localName + " from server " + serverAlias)
                .operationPreview(serverAlias + "/" + remoteToolName + " " + safePreview(call.getInput()))
                .metadata(Map.of(
                        "mcp_server", serverAlias,
                        "mcp_tool", remoteToolName,
                        "mcp_permission", permissionLevel.name()
                ))
                .build();

        if (!isReadOnly) {
            decision.setPolicyFingerprint(computeFingerprint(call));
        }

        return decision;
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> arguments;
            if (call.getInput() == null || call.getInput().isMissingNode()) {
                arguments = Map.of();
            } else {
                try {
                    arguments = jsonMapper.readValue(call.getInput().toString(),
                            new io.modelcontextprotocol.json.TypeRef<>() {});
                } catch (java.io.IOException e) {
                    long elapsed = System.currentTimeMillis() - startedAt;
                    return ToolResult.failure("mcp_call_failed", "Failed to parse input: " + e.getMessage(), elapsed);
                }
            }

            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(remoteToolName, arguments);
            McpSchema.CallToolResult mcpResult = client.callTool(request);
            long elapsed = System.currentTimeMillis() - startedAt;

            ToolResult result = resultMapper.map(mcpResult, elapsed);
            if (!result.isSuccess()) {
                log.warn("MCP tool {} returned error: {}", localName, result.getErrorCode());
                metrics.recordMcpToolCall(serverAlias, remoteToolName, "error");
            } else {
                metrics.recordMcpToolCall(serverAlias, remoteToolName, "success");
            }
            metrics.recordMcpToolDuration(serverAlias, remoteToolName,
                    result.isSuccess() ? "success" : "error", elapsed);
            return result;

        } catch (io.modelcontextprotocol.spec.McpTransportException e) {
            long elapsed = System.currentTimeMillis() - startedAt;
            log.error("MCP transport error for {}: {}", localName, e.getMessage());
            metrics.recordMcpToolCall(serverAlias, remoteToolName, "transport_error");
            metrics.recordMcpToolDuration(serverAlias, remoteToolName, "transport_error", elapsed);
            return ToolResult.failure("mcp_unavailable", "MCP server unreachable: " + serverAlias, elapsed);
        } catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - startedAt;
            String errorCode = "mcp_call_failed";
            if (e.getMessage() != null && e.getMessage().contains("timeout")) {
                errorCode = "mcp_timeout";
            }
            log.error("MCP call failed for {}: {}", localName, e.getMessage());
            metrics.recordMcpToolCall(serverAlias, remoteToolName, errorCode);
            metrics.recordMcpToolDuration(serverAlias, remoteToolName, errorCode, elapsed);
            return ToolResult.failure(errorCode, "MCP tool call failed: " + e.getMessage(), elapsed);
        }
    }

    private String buildDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("[MCP server=").append(serverAlias).append("] ");
        String remoteDesc = remoteToolDef.description();
        if (remoteDesc != null && !remoteDesc.isBlank()) {
            // Remove control characters
            String cleaned = remoteDesc.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
            if (cleaned.length() > maxDescriptionChars) {
                cleaned = cleaned.substring(0, maxDescriptionChars);
            }
            sb.append(cleaned);
        } else {
            sb.append("MCP tool: ").append(remoteToolName);
        }
        return sb.toString();
    }

    private String buildInputSchema() {
        McpSchema.JsonSchema schema = remoteToolDef.inputSchema();
        if (schema == null) {
            return "{\"type\":\"object\"}";
        }
        try {
            String serialized = jsonMapper.writeValueAsString(schema);
            if (serialized.length() > maxSchemaChars) {
                log.warn("MCP tool {} schema truncated from {} to {} chars", localName, serialized.length(), maxSchemaChars);
                serialized = serialized.substring(0, maxSchemaChars);
            }
            return serialized;
        } catch (Exception e) {
            log.warn("Failed to serialize schema for {}: {}", localName, e.getMessage());
            return "{\"type\":\"object\"}";
        }
    }

    private String computeFingerprint(ToolCall call) {
        try {
            String inputJson = call.getInput() != null ? call.getInput().toString() : "{}";
            // Normalize: sort keys via deserialize/serialize round-trip
            Map<String, Object> normalized = jsonMapper.readValue(inputJson,
                    new io.modelcontextprotocol.json.TypeRef<>() {});
            String normalizedJson = jsonMapper.writeValueAsString(normalized);

            String material = serverAlias + ":" + remoteToolName + ":" + permissionLevel.name() + ":" + normalizedJson;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return "mcp_" + serverAlias + "_" + remoteToolName + "_" + System.identityHashCode(this);
        }
    }

    private String safePreview(com.fasterxml.jackson.databind.JsonNode input) {
        if (input == null) {
            return "{}";
        }
        String text = input.toString();
        // Redact sensitive-looking fields
        text = text.replaceAll("\"(password|passwd|secret|token|apiKey|api_key|authorization|cookie|credential)\"\\s*:\\s*\"[^\"]*\"",
                "\"$1\":\"<redacted>\"");
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    String getServerAlias() {
        return serverAlias;
    }

    String getRemoteToolName() {
        return remoteToolName;
    }

    private static class McpSchemaError extends RuntimeException {
        McpSchemaError(String message) {
            super(message);
        }
    }
}
