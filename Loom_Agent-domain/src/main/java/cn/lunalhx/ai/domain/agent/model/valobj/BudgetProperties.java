package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class BudgetProperties {
    private Boolean enabled = false;
    private Integer maxTotalTokens = 200000;
    private Integer reservedOutputTokens = 2048;
    private Integer estimatedCharsPerToken = 4;
    private BigDecimal inputPricePer1k = BigDecimal.ZERO;
    private BigDecimal outputPricePer1k = BigDecimal.ZERO;
    private BigDecimal maxTotalCost;
}
