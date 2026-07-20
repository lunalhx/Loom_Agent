package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;

@Data
public class ModelRecoveryProperties {
    private Integer escalatedMaxTokens = 8192;
    private Integer continuationMaxAttempts = 3;
    private String contextFallbackModel;
}
