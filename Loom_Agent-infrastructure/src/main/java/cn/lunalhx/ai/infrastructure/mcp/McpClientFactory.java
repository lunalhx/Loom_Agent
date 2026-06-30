package cn.lunalhx.ai.infrastructure.mcp;

import cn.lunalhx.ai.domain.tool.model.McpClientProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.net.http.HttpRequest;
import java.util.Map;

public class McpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(McpClientFactory.class);

    private final McpClientProperties properties;
    private final McpJsonMapper jsonMapper;

    public McpClientFactory(McpClientProperties properties, McpJsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    public McpSyncClient create(String serverAlias) {
        McpClientProperties.ServerConfig config = properties.getServers().get(serverAlias);
        if (config == null) {
            throw new IllegalArgumentException("Unknown MCP server: " + serverAlias);
        }

        McpSchema.Implementation clientInfo = new McpSchema.Implementation(
                "loom-agent-" + McpToolNameGenerator.sanitize(serverAlias),
                properties.getClientVersion() != null ? properties.getClientVersion() : "1.0"
        );

        long connectTimeout = config.getConnectTimeoutMs() != null
                ? config.getConnectTimeoutMs()
                : properties.getConnectTimeoutMs();
        long requestTimeout = config.getRequestTimeoutMs() != null
                ? config.getRequestTimeoutMs()
                : properties.getRequestTimeoutMs();

        McpClient.SyncSpec spec = switch (config.getTransport()) {
            case STDIO -> {
                if (config.getCommand() == null || config.getCommand().isBlank()) {
                    throw new IllegalArgumentException("STDIO server '" + serverAlias + "' requires command");
                }
                var transport = new SanitizedStdioClientTransport(
                        properties.getStdioEnvAllowlist(),
                        config.getCommand(),
                        config.getArgs(),
                        config.getEnv(),
                        jsonMapper
                );
                yield McpClient.sync(transport);
            }
            case STREAMABLE_HTTP -> {
                String baseUrl = config.getBaseUrl();
                if (baseUrl == null || baseUrl.isBlank()) {
                    throw new IllegalArgumentException("STREAMABLE_HTTP server '" + serverAlias + "' requires base-url");
                }
                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                    throw new IllegalArgumentException("base-url must be absolute http/https: " + baseUrl);
                }
                String endpoint = config.getEndpoint() != null ? config.getEndpoint() : "/mcp";

                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
                Map<String, String> headers = config.getHeaders();
                if (headers != null) {
                    for (var entry : headers.entrySet()) {
                        String value = entry.getValue();
                        if (value != null && !value.isBlank()) {
                            requestBuilder.header(entry.getKey(), value);
                        }
                    }
                }

                var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                        .jsonMapper(jsonMapper)
                        .endpoint(endpoint)
                        .requestBuilder(requestBuilder)
                        .build();
                yield McpClient.sync(transport);
            }
        };

        return spec
                .clientInfo(clientInfo)
                .requestTimeout(Duration.ofMillis(requestTimeout))
                .initializationTimeout(Duration.ofMillis(connectTimeout))
                .build();
    }
}
