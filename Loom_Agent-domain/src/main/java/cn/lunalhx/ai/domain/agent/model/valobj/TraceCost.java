package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceCost {

    private BigDecimal inputCost;
    private BigDecimal outputCost;
    private BigDecimal totalCost;

}
