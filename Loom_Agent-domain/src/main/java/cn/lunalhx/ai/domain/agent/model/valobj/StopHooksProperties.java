package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;

@Data
public class StopHooksProperties {
    private Boolean enabled = true;
    private IncompletePlanProperties incompletePlan = new IncompletePlanProperties();

    @Data
    public static class IncompletePlanProperties {
        private Boolean enabled = true;
        private Integer maxContinuations = 1;
        private Boolean rootOnly = true;
    }
}
