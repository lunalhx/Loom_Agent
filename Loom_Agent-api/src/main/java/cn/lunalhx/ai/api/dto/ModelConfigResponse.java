package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConfigResponse implements Serializable {

    private static final long serialVersionUID = 4060363144997942013L;

    private String provider;
    private String baseUrl;
    private String model;
    private Double temperature;
    private Integer maxTokens;
    private String apiKey;
    private Long connectTimeoutMs;
    private Long firstTokenTimeoutMs;
    private Long streamTimeoutMs;
    private Integer retryMaxAttempts;
    private List<String> allowedModels;

}
