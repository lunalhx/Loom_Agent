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
}
