package cn.lunalhx.ai.domain.tool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class ExtensionsConfig {
    private Map<String, McpServerConfig> mcpServers = new LinkedHashMap<>();
    private Map<String, SkillState> skills = new LinkedHashMap<>();
}