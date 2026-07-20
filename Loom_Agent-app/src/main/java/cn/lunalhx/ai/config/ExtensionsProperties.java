package cn.lunalhx.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "loom.extensions")
public class ExtensionsProperties {
    private String configPath;
    private boolean hotReload = true;
}
