package cn.lunalhx.ai.domain.tool.model;

public enum FilesystemAccess {
    READ,
    WRITE;

    public boolean includes(FilesystemAccess requested) {
        return this == WRITE || this == requested;
    }
}
