package cn.lunalhx.ai.domain.tool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class McpServerConfig {
    private boolean enabled = true;
    private boolean required = false;
    private String transport = "STDIO";
    private String baseUrl;
    private String endpoint = "/mcp";
    private String command;
    private List<String> args;
    private Map<String, String> env = new LinkedHashMap<>();
    private Map<String, String> headers = new LinkedHashMap<>();
    private List<String> enabledTools;
    private List<String> disabledTools;
    private String defaultPermission = "WRITE_CONFIRM";
    private Map<String, String> toolPermissions = new LinkedHashMap<>();
    private boolean blockPrivateIps = false;
    private List<String> blockedDomains;
}