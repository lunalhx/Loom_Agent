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
import java.util.List;
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
    private final McpInputSchemaSimplifier schemaSimplifier;
    private final AgentMetrics metrics;
    private final boolean blockPrivateIps;
    private final List<String> blockedDomains;

    public McpAgentTool(String serverAlias,
                        String remoteToolName,
                        String localName,
                        McpSyncClient client,
                        McpResultMapper resultMapper,
                        McpJsonMapper jsonMapper,
                        McpSchema.Tool remoteToolDef,
                        ToolPermissionLevel permissionLevel,
                        int maxDescriptionChars,
                        McpInputSchemaSimplifier schemaSimplifier,
                        AgentMetrics metrics,
                        boolean blockPrivateIps,
                        List<String> blockedDomains) {
        this.serverAlias = serverAlias;
        this.remoteToolName = remoteToolName;
        this.localName = localName;
        this.client = client;
        this.resultMapper = resultMapper;
        this.jsonMapper = jsonMapper;
        this.remoteToolDef = remoteToolDef;
        this.permissionLevel = permissionLevel;
        this.maxDescriptionChars = maxDescriptionChars;
        this.schemaSimplifier = schemaSimplifier;
        this.metrics = metrics;
        this.blockPrivateIps = blockPrivateIps;
        this.blockedDomains = blockedDomains != null ? blockedDomains : List.of();
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

            ToolResult urlCheck = validateUrl(remoteToolName, arguments, startedAt);
            if (urlCheck != null) {
                return urlCheck;
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

    private ToolResult validateUrl(String toolName, Map<String, Object> arguments, long startedAt) {
        if (!"browser_navigate".equals(remoteToolName) || arguments == null) {
            return null;
        }
        Object urlObj = arguments.get("url");
        if (!(urlObj instanceof String url) || url.isBlank()) {
            return null;
        }

        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase();

            if (blockPrivateIps && isPrivateHost(host)) {
                long elapsed = System.currentTimeMillis() - startedAt;
                return ToolResult.failure("url_blocked",
                        "禁止访问内网地址: " + host + " [" + url + "]", elapsed);
            }
            for (String blocked : blockedDomains) {
                if (host.equals(blocked) || host.endsWith("." + blocked)) {
                    long elapsed = System.currentTimeMillis() - startedAt;
                    return ToolResult.failure("url_blocked",
                            "禁止访问域名: " + host + " [" + url + "]", elapsed);
                }
            }
            return null;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startedAt;
            return ToolResult.failure("url_blocked", "URL 解析失败: " + e.getMessage(), elapsed);
        }
    }

    private boolean isPrivateHost(String host) {
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")
                || host.startsWith("192.168.") || host.startsWith("10.")
                || host.startsWith("172.") && is172Private(host)
                || host.equals("0.0.0.0") || host.equals("[::]")
                || host.endsWith(".local") || host.endsWith(".internal")) {
            return true;
        }
        return false;
    }

    private boolean is172Private(String host) {
        try {
            String[] parts = host.split("\\.");
            if (parts.length >= 2) {
                int second = Integer.parseInt(parts[1]);
                return second >= 16 && second <= 31;
            }
        } catch (NumberFormatException ignored) {
        }
        return false;
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
        return schemaSimplifier.simplify(remoteToolDef.inputSchema());
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
