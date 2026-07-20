package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;

@Data
public class StepBudgetProperties {
    private Boolean continuationEnabled = true;
    private Integer maxSegments = 5;
    private Integer childMaxSegments = 2;
    private Integer maxTotalSteps = 150;
    private Integer sameActionMaxRepeats = 2;
    private Integer sameFailureMaxRepeats = 2;
    private Integer noProgressMaxRounds = 3;
}
