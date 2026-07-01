package cn.lunalhx.ai.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum DeletionStatus {
    REQUESTED("REQUESTED"),
    WAITING_FOR_RUNS("WAITING_FOR_RUNS"),
    PURGING("PURGING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED");

    private final String value;
}
