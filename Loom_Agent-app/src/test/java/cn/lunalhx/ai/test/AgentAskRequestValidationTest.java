package cn.lunalhx.ai.test;

import cn.lunalhx.ai.api.dto.AgentAskRequest;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.Assert;
import org.junit.Test;

import java.util.Set;

public class AgentAskRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    public void testMaxStepsAllowsThirty() {
        AgentAskRequest request = AgentAskRequest.builder()
                .question("complex task")
                .maxSteps(30)
                .build();

        Set<ConstraintViolation<AgentAskRequest>> violations = validator.validate(request);

        Assert.assertTrue(violations.isEmpty());
    }

    @Test
    public void testMaxStepsRejectsThirtyOne() {
        AgentAskRequest request = AgentAskRequest.builder()
                .question("complex task")
                .maxSteps(31)
                .build();

        Set<ConstraintViolation<AgentAskRequest>> violations = validator.validate(request);

        Assert.assertFalse(violations.isEmpty());
    }

    @Test
    public void testRuntimeDefaultMaxStepsIsThirty() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();

        Assert.assertEquals(Integer.valueOf(30), properties.getMaxSteps());
        Assert.assertEquals(Long.valueOf(1800000L), properties.getTotalTimeoutMs());
        Assert.assertEquals(Long.valueOf(120000L), properties.getStepTimeoutMs());
    }

    @Test
    public void testMaxSegmentsAllowsTen() {
        AgentAskRequest request = AgentAskRequest.builder()
                .question("complex task")
                .maxSegments(10)
                .build();

        Set<ConstraintViolation<AgentAskRequest>> violations = validator.validate(request);

        Assert.assertTrue(violations.isEmpty());
    }

    @Test
    public void testMaxSegmentsRejectsEleven() {
        AgentAskRequest request = AgentAskRequest.builder()
                .question("complex task")
                .maxSegments(11)
                .build();

        Set<ConstraintViolation<AgentAskRequest>> violations = validator.validate(request);

        Assert.assertFalse(violations.isEmpty());
    }

    @Test
    public void testMaxSegmentsRejectsZero() {
        AgentAskRequest request = AgentAskRequest.builder()
                .question("complex task")
                .maxSegments(0)
                .build();

        Set<ConstraintViolation<AgentAskRequest>> violations = validator.validate(request);

        Assert.assertFalse(violations.isEmpty());
    }

    @Test
    public void testStepBudgetDefaults() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        AgentRuntimeProperties.StepBudgetProperties stepBudget = properties.getStepBudget();

        Assert.assertNotNull(stepBudget);
        Assert.assertTrue(stepBudget.getContinuationEnabled());
        Assert.assertEquals(Integer.valueOf(5), stepBudget.getMaxSegments());
        Assert.assertEquals(Integer.valueOf(2), stepBudget.getChildMaxSegments());
        Assert.assertEquals(Integer.valueOf(150), stepBudget.getMaxTotalSteps());
        Assert.assertEquals(Integer.valueOf(2), stepBudget.getSameActionMaxRepeats());
        Assert.assertEquals(Integer.valueOf(2), stepBudget.getSameFailureMaxRepeats());
        Assert.assertEquals(Integer.valueOf(3), stepBudget.getNoProgressMaxRounds());
    }
}
