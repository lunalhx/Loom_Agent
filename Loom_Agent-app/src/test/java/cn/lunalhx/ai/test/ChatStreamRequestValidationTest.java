package cn.lunalhx.ai.test;

import cn.lunalhx.ai.api.dto.ChatStreamRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.Assert;
import org.junit.Test;

import java.util.Set;

public class ChatStreamRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    public void testValidRequest() {
        ChatStreamRequest request = ChatStreamRequest.builder()
                .message("hello")
                .model("deepseek-v4-flash")
                .temperature(0.7D)
                .maxTokens(1024)
                .build();

        Set<ConstraintViolation<ChatStreamRequest>> violations = validator.validate(request);

        Assert.assertTrue(violations.isEmpty());
    }

    @Test
    public void testBlankMessage() {
        ChatStreamRequest request = ChatStreamRequest.builder()
                .message("")
                .build();

        Set<ConstraintViolation<ChatStreamRequest>> violations = validator.validate(request);

        Assert.assertFalse(violations.isEmpty());
    }

    @Test
    public void testInvalidModel() {
        ChatStreamRequest request = ChatStreamRequest.builder()
                .message("hello")
                .model("deepseek-chat")
                .build();

        Set<ConstraintViolation<ChatStreamRequest>> violations = validator.validate(request);

        Assert.assertFalse(violations.isEmpty());
    }

}
