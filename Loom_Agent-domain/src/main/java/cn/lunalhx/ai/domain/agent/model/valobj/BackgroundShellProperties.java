package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;

@Data
public class BackgroundShellProperties {
    private boolean enabled = true;
    private int globalMaxTasks = 8;
    private int perRunMaxTasks = 4;
    private int ioThreads = 4;
    private long foregroundYieldMs = 10_000L;
    private long maxForegroundYieldMs = 30_000L;
    private long taskRetentionHours = 24;
    private String dataDir;
}
