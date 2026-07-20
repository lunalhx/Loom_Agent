package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.service.ToolAssembler;
import cn.lunalhx.ai.infrastructure.mcp.ExtensionsConfigLoader;
import cn.lunalhx.ai.infrastructure.mcp.McpClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class ExtensionsConfigHotReloader {

    private static final Logger log = LoggerFactory.getLogger(ExtensionsConfigHotReloader.class);

    public ExtensionsConfigHotReloader(ExtensionsConfigLoader loader,
                                       McpClientManager manager,
                                       ToolRegistry registry,
                                       List<AgentTool> builtInTools,
                                       LoomConfigWatcher watcher,
                                       ExtensionsConfigRegistry extensions) {
        watcher.register(loader.configPath(), () -> {
            extensions.reload(config -> {
                if (manager == null) {
                    return;
                }
                var replacement = loader.toMcpProperties(config);
                manager.reload(replacement,
                        mcpTools -> registry.validateSnapshot(ToolAssembler.assemble(builtInTools, mcpTools)));
                registry.replace(ToolAssembler.assemble(builtInTools, manager.tools()));
            });
            log.info("Extensions configuration reloaded: version={}", extensions.capture().version());
        });
    }

}
