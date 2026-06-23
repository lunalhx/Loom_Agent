package cn.lunalhx.ai.domain.model.valobj;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Data
public class ModelRuntimeProperties {

    private Long connectTimeoutMs = 10000L;
    private Long firstTokenTimeoutMs = 30000L;
    private Long streamTimeoutMs = 120000L;
    private Integer retryMaxAttempts = 3;
    private Long retryBackoffInitialMs = 300L;
    private Long retryBackoffMaxMs = 3000L;
    private Integer maxMessageLength = 20000;
    private List<String> allowedModels = new ArrayList<>(List.of("deepseek-v4-flash", "deepseek-v4-pro"));
    private ResilienceProperties resilience = new ResilienceProperties();

    public String normalizeModel(String requestedModel, String defaultModel) {
        String model = StringUtils.defaultIfBlank(requestedModel, defaultModel);
        if (!allowedModels.contains(model)) {
            throw new ModelGatewayException(ModelErrorCode.INVALID_REQUEST, "不支持的模型：" + model, false, null, null);
        }
        return model;
    }

    @Data
    public static class ResilienceProperties {

        private Boolean enabled = true;
        private Integer retryMaxAttempts = 3;
        private Long retryBackoffInitialMs = 300L;
        private Long retryBackoffMaxMs = 3000L;
        private Float circuitFailureRateThreshold = 50.0F;
        private Float circuitSlowCallRateThreshold = 50.0F;
        private Long circuitSlowCallDurationMs = 30000L;
        private Integer circuitSlidingWindowSize = 10;
        private Long circuitOpenStateWaitMs = 30000L;
        private Integer circuitHalfOpenPermittedCalls = 2;

    }

}
