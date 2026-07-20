package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;

@Data
public class ExecutionGuardProperties {
    private Boolean planBeforeWrite = false;
    private Boolean verificationAfterWrite = false;
    private Integer maxVerificationContinuations = 2;
}
