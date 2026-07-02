package cn.lunalhx.ai.infrastructure.gateway.diagnostics;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用 {@link PromptCacheDiagnosticProperties} 的绑定。
 *
 * <p>独立成类的原因：infrastructure 模块没有自己的 application class，
 * 通过显式 {@code @EnableConfigurationProperties} 让 properties 在该模块被加载。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PromptCacheDiagnosticProperties.class)
public class PromptCacheDiagnosticAutoConfig {
}
