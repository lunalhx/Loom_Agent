package cn.lunalhx.ai.config;

import lombok.Data;

@Data
public class PersistenceProperties {

    private Mode mode = Mode.SQLITE;
    private String dataDir = System.getProperty("user.home") + "/.loom-agent";
    private int busyTimeoutMs = 5000;
    private int maxPoolSize = 4;

    public enum Mode {
        SQLITE,
        MEMORY
    }

    public boolean isExplicitMemory() {
        return mode == Mode.MEMORY;
    }

    public boolean isSqlite() {
        return mode == Mode.SQLITE;
    }
}
