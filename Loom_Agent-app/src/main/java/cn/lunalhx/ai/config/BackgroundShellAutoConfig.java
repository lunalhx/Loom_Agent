package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.common.LoomPaths;
import cn.lunalhx.ai.domain.tool.sandbox.SandboxProvider;
import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
import cn.lunalhx.ai.domain.tool.adapter.port.TaskLogReader;
import cn.lunalhx.ai.domain.tool.model.BackgroundShellTask;
import cn.lunalhx.ai.domain.tool.model.BackgroundTaskStatus;
import cn.lunalhx.ai.domain.tool.service.BackgroundTaskCancelService;
import cn.lunalhx.ai.infrastructure.tool.BackgroundProcessManager;
import cn.lunalhx.ai.infrastructure.tool.SandboxEnvPolicy;
import cn.lunalhx.ai.infrastructure.tool.LocalSandboxProvider;
import cn.lunalhx.ai.infrastructure.tool.SeekableTaskLogReader;
import cn.lunalhx.ai.service.DefaultBackgroundTaskCancelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
public class BackgroundShellAutoConfig {

    private static final Logger log = LoggerFactory.getLogger(BackgroundShellAutoConfig.class);

    @Bean
    public SandboxEnvPolicy sandboxEnvPolicy(AgentRuntimeProperties properties) {
        cn.lunalhx.ai.domain.agent.model.valobj.SandboxProperties sandbox = properties.getSandbox();
        SandboxEnvPolicy.Mode mode;
        try {
            mode = SandboxEnvPolicy.Mode.valueOf(sandbox.getEnvMode().toUpperCase());
        } catch (Exception e) {
            mode = SandboxEnvPolicy.Mode.BLACKLIST;
        }
        Set<String> allowlist = sandbox.getEnvAllowlist() != null
                ? new HashSet<>(sandbox.getEnvAllowlist()) : Set.of();
        Set<String> extraBlocklist = sandbox.getEnvExtraBlocklist() != null
                ? new HashSet<>(sandbox.getEnvExtraBlocklist()) : Set.of();
        return new SandboxEnvPolicy(mode, allowlist, extraBlocklist);
    }

    @Bean
    public BackgroundProcessManager backgroundProcessManager(AgentRuntimeProperties properties,
                                                              BackgroundShellTaskRepository taskRepository,
                                                              SandboxEnvPolicy sandboxEnvPolicy,
                                                              LoomPaths loomPaths) {
        cn.lunalhx.ai.domain.agent.model.valobj.BackgroundShellProperties bg = properties.getBackgroundShell();
        String dataDir = bg.getDataDir();
        if (dataDir == null || dataDir.isBlank()) {
            dataDir = loomPaths.backgroundTasks().toString();
        }
        Path taskLogDir = loomPaths.resolveWorkspacePath(dataDir, loomPaths.backgroundTasks());

        long defaultTimeout = properties.getShellTimeoutMs() != null ? properties.getShellTimeoutMs() : 120_000L;
        long yield = bg.getForegroundYieldMs() > 0 ? bg.getForegroundYieldMs() : 10_000L;
        long maxTimeout = properties.getShellMaxTimeoutMs() > 0 ? properties.getShellMaxTimeoutMs() : 600_000L;

        BackgroundProcessManager manager = new BackgroundProcessManager(
                taskLogDir, defaultTimeout, yield, maxTimeout,
                bg.getGlobalMaxTasks(), bg.getPerRunMaxTasks(), bg.getIoThreads(),
                taskRepository, sandboxEnvPolicy);

        log.info("BackgroundProcessManager initialized: logDir={} globalMax={} perRunMax={} yieldMs={}",
                taskLogDir, bg.getGlobalMaxTasks(), bg.getPerRunMaxTasks(), yield);

        return manager;
    }

    @Bean(destroyMethod = "close")
    public SandboxProvider sandboxProvider(AgentRuntimeProperties properties,
                                           BackgroundProcessManager processManager,
                                           LoomPaths loomPaths) {
        var sandbox = properties.getSandbox();
        return new LocalSandboxProvider(processManager, loomPaths,
                sandbox.getMaxCachedConversations(), sandbox.getIdleTtlMs());
    }

    @Bean
    public TaskLogReader taskLogReader() {
        return new SeekableTaskLogReader();
    }

    @Bean
    public BackgroundTaskCancelService backgroundTaskCancelService(
            BackgroundShellTaskRepository taskRepository,
            BackgroundProcessManager processManager) {
        return new DefaultBackgroundTaskCancelService(taskRepository, processManager);
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
