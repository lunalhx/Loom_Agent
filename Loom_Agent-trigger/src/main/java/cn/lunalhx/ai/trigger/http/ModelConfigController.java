package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.dto.ModelConfigResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.types.enums.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/model")
public class ModelConfigController {

    private final Environment environment;
    private final ModelRuntimeProperties modelRuntimeProperties;

    @GetMapping("/config")
    public Response<ModelConfigResponse> config() {
        ModelConfigResponse config = ModelConfigResponse.builder()
                .provider("deepseek")
                .baseUrl(environment.getProperty("spring.ai.deepseek.base-url", "https://api.deepseek.com"))
                .model(environment.getProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash"))
                .temperature(environment.getProperty("spring.ai.deepseek.chat.temperature", Double.class, 0.7D))
                .maxTokens(environment.getProperty("spring.ai.deepseek.chat.max-tokens", Integer.class, 2048))
                .apiKey(mask(environment.getProperty("spring.ai.deepseek.api-key", "")))
                .connectTimeoutMs(modelRuntimeProperties.getConnectTimeoutMs())
                .firstTokenTimeoutMs(modelRuntimeProperties.getFirstTokenTimeoutMs())
                .streamTimeoutMs(modelRuntimeProperties.getStreamTimeoutMs())
                .retryMaxAttempts(modelRuntimeProperties.getRetryMaxAttempts())
                .allowedModels(modelRuntimeProperties.getAllowedModels())
                .build();
        return Response.<ModelConfigResponse>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(config)
                .build();
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
