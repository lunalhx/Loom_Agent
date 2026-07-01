package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
import cn.lunalhx.ai.domain.tool.model.BackgroundShellTask;
import cn.lunalhx.ai.domain.tool.model.BackgroundTaskStatus;
import cn.lunalhx.ai.infrastructure.tool.BackgroundProcessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class BackgroundShellAutoConfig {

    private static final Logger log = LoggerFactory.getLogger(BackgroundShellAutoConfig.class);

    @Bean
    public BackgroundProcessManager backgroundProcessManager(AgentRuntimeProperties properties) {
        AgentRuntimeProperties.BackgroundShellProperties bg = properties.getBackgroundShell();
        String dataDir = bg.getDataDir();
        if (dataDir == null || dataDir.isBlank()) {
            String loomDataDir = System.getenv().getOrDefault("LOOM_DATA_DIR",
                    System.getProperty("user.home") + "/.loom-agent");
            dataDir = loomDataDir + "/background-tasks";
        }
        Path taskLogDir = Path.of(dataDir);

        long defaultTimeout = properties.getShellTimeoutMs() != null ? properties.getShellTimeoutMs() : 120_000L;
        long yield = bg.getForegroundYieldMs() > 0 ? bg.getForegroundYieldMs() : 10_000L;
        long maxTimeout = properties.getShellMaxTimeoutMs() > 0 ? properties.getShellMaxTimeoutMs() : 600_000L;

        BackgroundProcessManager manager = new BackgroundProcessManager(
                taskLogDir, defaultTimeout, yield, maxTimeout,
                bg.getGlobalMaxTasks(), bg.getPerRunMaxTasks(), bg.getIoThreads());

        log.info("BackgroundProcessManager initialized: logDir={} globalMax={} perRunMax={} yieldMs={}",
                taskLogDir, bg.getGlobalMaxTasks(), bg.getPerRunMaxTasks(), yield);

        return manager;
    }

    @Bean
    public Object backgroundTaskStartupRecovery(BackgroundShellTaskRepository taskRepository) {
        List<BackgroundShellTask> stale = taskRepository.findStaleRunning();
        if (!stale.isEmpty()) {
            log.info("Marking {} stale background tasks as LOST", stale.size());
            for (BackgroundShellTask t : stale) {
                t.setStatus(BackgroundTaskStatus.LOST);
                t.setErrorCode("process_lost");
                t.setErrorMessage("应用重启，进程已丢失");
                t.setCompletedAt(java.time.Instant.now());
                taskRepository.save(t);
            }
        }
        return new Object();
    }

}
