package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.common.LoomPaths;
import lombok.Data;

@Data
public class PersistenceProperties {

    private Mode mode = Mode.SQLITE;
    private String dataDir = LoomPaths.system().home().toString();
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
