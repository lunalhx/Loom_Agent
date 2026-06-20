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

    public String normalizeModel(String requestedModel, String defaultModel) {
        String model = StringUtils.defaultIfBlank(requestedModel, defaultModel);
        if (!allowedModels.contains(model)) {
            throw new ModelGatewayException(ModelErrorCode.INVALID_REQUEST, "不支持的模型：" + model, false, null, null);
        }
        return model;
    }

}
