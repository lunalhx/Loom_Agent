package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.infrastructure.gateway.HttpModelGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the direct HTTP {@link ModelGateway} from the runtime model properties.
 * Provider/base-url/api-key are bound by the CLI from arguments/.env before
 * the Spring context starts.
 */
@Configuration(proxyBeanMethods = false)
public class ModelGatewayAutoConfig {

    @Bean
    public ModelGateway modelGateway(ModelRuntimeProperties properties) {
        return HttpModelGateway.fromProperties(properties);
    }
}
