package cn.lunalhx.ai.test;

import cn.lunalhx.ai.api.dto.AgentAskRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentAskRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    public void testMaxStepsRejectsThirtyOne() {
        AgentAskRequest request = AgentAskRequest.builder()
                .question("complex task")
                .maxSteps(31)
                .build();

        Set<ConstraintViolation<AgentAskRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty());
    }
}
