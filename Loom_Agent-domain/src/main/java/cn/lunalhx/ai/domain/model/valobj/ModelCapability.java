package cn.lunalhx.ai.domain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModelCapability {

    private String model;
    private Long contextLength = 1000000L;
    private Integer maxOutputTokens = 384000;
    private Boolean supportsJsonOutput = true;
    private Boolean supportsToolCalls = true;

}
