package cn.lunalhx.ai.domain.agent.model.valobj;

public enum UndoSnapshotStatus {
    OPEN,
    SUSPENDED,
    READY,
    NO_CHANGES,
    UNAVAILABLE,
    UNDOING,
    UNDONE,
    EXPIRED,
    FAILED
}
