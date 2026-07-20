package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;

@Data
public class UndoProperties {
    private boolean enabled = true;
    private int retentionHours = 168;
    private int maxChangedFiles = 500;
    private long maxChangedBytes = 104_857_600L;
    private long commandTimeoutMs = 30_000L;
    private long cleanupIntervalMs = 3_600_000L;
}
