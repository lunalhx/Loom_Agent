package cn.lunalhx.ai.domain.tool.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class McpClientProperties {

    private boolean enabled = false;
    private String clientName = "loom-agent";
    private String clientVersion = "1.0";
    private long connectTimeoutMs = 10000;
    private long initializationTimeoutMs = 10000;
    private long requestTimeoutMs = 60000;
    private int maxToolsPerServer = 64;
    private int maxDescriptionChars = 1000;
    private int maxSchemaChars = 16000;
    private int maxResultChars = 100000;
    private List<String> stdioEnvAllowlist = List.of("PATH", "HOME", "TMPDIR", "LANG");
    private Map<String, ServerConfig> servers = new HashMap<>();

    @Data
    public static class ServerConfig {
        private boolean enabled = true;
        private boolean required = false;
        private Transport transport = Transport.STDIO;
        private String command;
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new HashMap<>();
        private String baseUrl;
        private String endpoint = "/mcp";
        private Map<String, String> headers = new HashMap<>();
        private List<String> enabledTools = new ArrayList<>();
        private List<String> disabledTools = new ArrayList<>();
        private String defaultPermission = "WRITE_CONFIRM";
        private Map<String, String> toolPermissions = new HashMap<>();
        private Long connectTimeoutMs;
        private Long requestTimeoutMs;

        public enum Transport {
            STDIO,
            STREAMABLE_HTTP
        }
    }
}
