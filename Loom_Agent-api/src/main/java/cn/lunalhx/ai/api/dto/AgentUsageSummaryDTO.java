package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentUsageSummaryDTO implements Serializable {

    private static final long serialVersionUID = 5227892274571996057L;

    private String runId;
    private String status;
    private String traceId;
    private Long inputTokens;
    private Long outputTokens;
    private Long totalTokens;
    private Long cacheHitTokens;
    private Long cacheMissTokens;
    private BigDecimal cacheHitRate;

}
