package cn.lunalhx.ai.domain.model.valobj;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private Map<String, ModelCapability> modelCapabilities = defaultCapabilities();
    private Map<String, ModelPricing> modelPricing = defaultPricing();

    public String normalizeModel(String requestedModel, String defaultModel) {
        String model = StringUtils.defaultIfBlank(requestedModel, defaultModel);
        if (!allowedModels.contains(model)) {
            throw new ModelGatewayException(ModelErrorCode.INVALID_REQUEST, "不支持的模型：" + model, false, null, null);
        }
        return model;
    }

    public ModelCapability capability(String model) {
        ModelCapability capability = modelCapabilities.get(model);
        if (capability == null) {
            throw new ModelGatewayException(ModelErrorCode.MODEL_CAPABILITY_MISMATCH,
                    "未配置模型能力：" + model, false, null, null);
        }
        capability.setModel(model);
        return capability;
    }

    public ModelPricing pricing(String model) {
        return modelPricing.getOrDefault(model, new ModelPricing());
    }

    private static Map<String, ModelCapability> defaultCapabilities() {
        Map<String, ModelCapability> capabilities = new LinkedHashMap<>();
        capabilities.put("deepseek-v4-flash", new ModelCapability("deepseek-v4-flash", 1000000L, 384000, true, true));
        capabilities.put("deepseek-v4-pro", new ModelCapability("deepseek-v4-pro", 1000000L, 384000, true, true));
        return capabilities;
    }

    private static Map<String, ModelPricing> defaultPricing() {
        Map<String, ModelPricing> pricing = new LinkedHashMap<>();
        pricing.put("deepseek-v4-flash", new ModelPricing());
        pricing.put("deepseek-v4-pro", new ModelPricing());
        return pricing;
    }

    @Data
    public static class ResilienceProperties {

        private Boolean enabled = true;
        private Integer retryMaxAttempts = 10;
        private Long retryBackoffInitialMs = 500L;
        private Long retryBackoffMaxMs = 32000L;
        private Integer overloadFallbackThreshold = 3;
        private String fallbackModel = "deepseek-v4-pro";
        private String fallbackStickinessScope = "current_step";
        private Float circuitFailureRateThreshold = 50.0F;
        private Float circuitSlowCallRateThreshold = 50.0F;
        private Long circuitSlowCallDurationMs = 30000L;
        private Integer circuitSlidingWindowSize = 10;
        private Long circuitOpenStateWaitMs = 30000L;
        private Integer circuitHalfOpenPermittedCalls = 2;

    }

}
