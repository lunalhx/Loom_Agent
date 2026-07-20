package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.dto.ModelConfigResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties.ProviderConfig;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/model")
public class ModelConfigController {

    private final ModelRuntimeProperties modelRuntimeProperties;

    @GetMapping("/config")
    public Response<ModelConfigResponse> config() {
        ProviderConfig active = modelRuntimeProperties.activeProvider();
        String provider = modelRuntimeProperties.getProvider();

        ModelConfigResponse config = ModelConfigResponse.builder()
                .provider(provider)
                .baseUrl(active.getBaseUrl())
                .model(modelRuntimeProperties.resolvedDefaultModel())
                .temperature(active.getTemperature())
                .maxTokens(active.getMaxTokens())
                .apiKey(mask(active.getApiKey()))
                .connectTimeoutMs(modelRuntimeProperties.getConnectTimeoutMs())
                .firstTokenTimeoutMs(modelRuntimeProperties.getFirstTokenTimeoutMs())
                .streamTimeoutMs(modelRuntimeProperties.getStreamTimeoutMs())
                .retryMaxAttempts(modelRuntimeProperties.getRetryMaxAttempts())
                .allowedModels(modelRuntimeProperties.getAllowedModels())
                .build();
        return Response.success(config);
    }

    private String mask(String apiKey) {
        if (StringUtils.isBlank(apiKey)) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

}
