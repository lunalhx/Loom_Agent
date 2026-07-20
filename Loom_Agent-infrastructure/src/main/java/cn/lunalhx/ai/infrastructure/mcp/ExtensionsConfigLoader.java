package cn.lunalhx.ai.infrastructure.mcp;

import cn.lunalhx.ai.domain.tool.model.ExtensionsConfig;
import cn.lunalhx.ai.domain.tool.model.McpClientProperties;
import cn.lunalhx.ai.domain.tool.model.McpServerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

public class ExtensionsConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ExtensionsConfigLoader.class);
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private final Path configPath;
    private final ObjectMapper objectMapper;
    private final McpClientProperties deprecatedProperties;
    private final AtomicBoolean migrationWarningLogged = new AtomicBoolean();

    public ExtensionsConfigLoader(Path configPath, ObjectMapper objectMapper,
                                   McpClientProperties deprecatedProperties) {
        this.configPath = configPath;
        this.objectMapper = objectMapper;
        this.deprecatedProperties = deprecatedProperties;
    }

    public ExtensionsConfig load() {
        if (Files.exists(configPath)) {
            if (deprecatedProperties != null && deprecatedProperties.getServers() != null
                    && !deprecatedProperties.getServers().isEmpty()
                    && migrationWarningLogged.compareAndSet(false, true)) {
                log.warn("Both {} and deprecated loom.mcp.servers are configured; the extensions file takes precedence",
                        configPath);
            }
            return loadFromJson();
        }
        if (deprecatedProperties != null && deprecatedProperties.getServers() != null
                && !deprecatedProperties.getServers().isEmpty()) {
            log.warn("MCP servers configured via loom.mcp.servers (YAML) is deprecated. "
                    + "Please migrate to {}", configPath);
            return loadFromYaml();
        }
        log.info("No MCP extensions config found at {} and no YAML servers configured. "
                + "MCP tools will be empty.", configPath);
        return new ExtensionsConfig();
    }

    public McpClientProperties loadMcpProperties() {
        return toMcpProperties(load());
    }

    public McpClientProperties toMcpProperties(ExtensionsConfig extensions) {
        McpClientProperties target = copyTuning(deprecatedProperties);
        Map<String, McpClientProperties.ServerConfig> servers = new java.util.TreeMap<>();
        if (extensions.getMcpServers() != null) {
            extensions.getMcpServers().forEach((alias, source) -> servers.put(alias, toServerConfig(source)));
        }
        target.setServers(servers);
        return target;
    }

    public Path configPath() {
        return configPath;
    }

    private McpClientProperties copyTuning(McpClientProperties source) {
        McpClientProperties target = new McpClientProperties();
        if (source == null) {
            target.setEnabled(true);
            return target;
        }
        target.setEnabled(source.isEnabled());
        target.setClientName(source.getClientName());
        target.setClientVersion(source.getClientVersion());
        target.setConnectTimeoutMs(source.getConnectTimeoutMs());
        target.setInitializationTimeoutMs(source.getInitializationTimeoutMs());
        target.setRequestTimeoutMs(source.getRequestTimeoutMs());
        target.setMaxToolsPerServer(source.getMaxToolsPerServer());
        target.setMaxDescriptionChars(source.getMaxDescriptionChars());
        target.setMaxSchemaChars(source.getMaxSchemaChars());
        target.setMaxPropertyDescriptionChars(source.getMaxPropertyDescriptionChars());
        target.setMaxProperties(source.getMaxProperties());
        target.setMaxEnumValues(source.getMaxEnumValues());
        target.setMaxResultChars(source.getMaxResultChars());
        target.setStdioEnvAllowlist(source.getStdioEnvAllowlist());
        return target;
    }

    private McpClientProperties.ServerConfig toServerConfig(McpServerConfig source) {
        McpClientProperties.ServerConfig target = new McpClientProperties.ServerConfig();
        target.setEnabled(source.isEnabled());
        target.setRequired(source.isRequired());
        try {
            target.setTransport(McpClientProperties.ServerConfig.Transport.valueOf(
                    source.getTransport().toUpperCase()));
        } catch (Exception e) {
            throw new IllegalStateException("Unsupported MCP transport: " + source.getTransport());
        }
        target.setBaseUrl(source.getBaseUrl());
        target.setEndpoint(source.getEndpoint());
        target.setCommand(source.getCommand());
        target.setArgs(source.getArgs() == null ? java.util.List.of() : source.getArgs());
        target.setEnv(source.getEnv() == null ? Map.of() : source.getEnv());
        target.setHeaders(source.getHeaders() == null ? Map.of() : source.getHeaders());
        target.setEnabledTools(source.getEnabledTools() == null ? java.util.List.of() : source.getEnabledTools());
        target.setDisabledTools(source.getDisabledTools() == null ? java.util.List.of() : source.getDisabledTools());
        target.setDefaultPermission(source.getDefaultPermission());
        target.setToolPermissions(source.getToolPermissions() == null ? Map.of() : source.getToolPermissions());
        target.setBlockPrivateIps(source.isBlockPrivateIps());
        target.setBlockedDomains(source.getBlockedDomains() == null ? java.util.List.of() : source.getBlockedDomains());
        return target;
    }

    private ExtensionsConfig loadFromJson() {
        try {
            String raw = Files.readString(configPath);
            String resolved = resolveEnv(raw);
            ExtensionsConfig config = objectMapper.readValue(resolved, ExtensionsConfig.class);
            log.info("Loaded extensions config from {}: {} MCP servers, {} skills",
                    configPath,
                    config.getMcpServers() != null ? config.getMcpServers().size() : 0,
                    config.getSkills() != null ? config.getSkills().size() : 0);
            return config;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load extensions config from " + configPath + ": " + e.getMessage(), e);
        }
    }

    private ExtensionsConfig loadFromYaml() {
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        for (var entry : deprecatedProperties.getServers().entrySet()) {
            McpClientProperties.ServerConfig yamlConfig = entry.getValue();
            McpServerConfig config = new McpServerConfig();
            config.setEnabled(yamlConfig.isEnabled());
            config.setRequired(yamlConfig.isRequired());
            config.setTransport(yamlConfig.getTransport().name());
            config.setBaseUrl(yamlConfig.getBaseUrl());
            config.setEndpoint(yamlConfig.getEndpoint());
            config.setCommand(yamlConfig.getCommand());
            config.setArgs(yamlConfig.getArgs());
            config.setEnv(yamlConfig.getEnv());
            config.setHeaders(yamlConfig.getHeaders());
            config.setEnabledTools(yamlConfig.getEnabledTools());
            config.setDisabledTools(yamlConfig.getDisabledTools());
            config.setDefaultPermission(yamlConfig.getDefaultPermission());
            config.setToolPermissions(yamlConfig.getToolPermissions());
            config.setBlockPrivateIps(yamlConfig.isBlockPrivateIps());
            config.setBlockedDomains(yamlConfig.getBlockedDomains());
            servers.put(entry.getKey(), config);
        }
        ExtensionsConfig result = new ExtensionsConfig();
        result.setMcpServers(servers);
        return result;
    }

    static String resolveEnv(String input) {
        Matcher m = ENV_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            String value = System.getenv(varName);
            if (value == null) {
                throw new IllegalStateException(
                        "Environment variable not set: " + varName + " in extensions config");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        String result = sb.toString();
        if (ENV_PATTERN.matcher(result).find()) {
            return resolveEnv(result);
        }
        return result;
    }
}
