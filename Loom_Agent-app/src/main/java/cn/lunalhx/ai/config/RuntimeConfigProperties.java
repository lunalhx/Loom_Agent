package cn.lunalhx.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "loom.runtime-config")
public class RuntimeConfigProperties {
    private String path;
    private boolean hotReload = true;
}
