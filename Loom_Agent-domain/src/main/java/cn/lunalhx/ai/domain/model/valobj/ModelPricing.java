package cn.lunalhx.ai.domain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModelPricing {

    private BigDecimal inputPricePer1k = BigDecimal.ZERO;
    private BigDecimal outputPricePer1k = BigDecimal.ZERO;

}
