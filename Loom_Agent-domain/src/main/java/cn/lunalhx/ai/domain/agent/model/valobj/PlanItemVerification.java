package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanItemVerification {

    private String command;
    private Boolean passed;
    private Integer exitCode;
    private String summary;

}
