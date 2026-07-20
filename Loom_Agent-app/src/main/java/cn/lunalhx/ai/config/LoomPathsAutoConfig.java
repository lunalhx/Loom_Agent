package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.common.LoomPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LoomPathsAutoConfig {

    @Bean
    public LoomPaths loomPaths() {
        return LoomPaths.system();
    }
}
