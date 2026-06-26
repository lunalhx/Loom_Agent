package cn.lunalhx.ai.config;

import lombok.Data;

@Data
public class PersistenceProperties {

    private Mode mode = Mode.AUTO;
    private Boolean required = false;

    public enum Mode {
        AUTO,
        MYSQL,
        MEMORY
    }

    public boolean isExplicitMemory() {
        return mode == Mode.MEMORY;
    }

    public boolean isExplicitMysql() {
        return mode == Mode.MYSQL;
    }

    public boolean isAuto() {
        return mode == Mode.AUTO;
    }
}
