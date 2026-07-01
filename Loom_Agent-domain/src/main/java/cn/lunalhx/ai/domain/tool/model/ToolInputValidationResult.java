package cn.lunalhx.ai.domain.tool.model;

import java.util.List;

public record ToolInputValidationResult(
        boolean valid,
        List<FieldError> errors
) {
    public static ToolInputValidationResult success() {
        return new ToolInputValidationResult(true, List.of());
    }

    public static ToolInputValidationResult failure(List<FieldError> errors) {
        return new ToolInputValidationResult(false, errors == null ? List.of() : errors);
    }

    public record FieldError(String pointer, String keyword, String message) {
    }
}
