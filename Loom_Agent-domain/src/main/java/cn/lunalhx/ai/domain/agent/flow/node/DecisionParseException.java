package cn.lunalhx.ai.domain.agent.flow.node;

import java.util.Map;

/**
 * Thrown when the model output cannot be parsed into a valid decision.
 *
 * <p>Carries structured error information so callers can build
 * field-level repair hints for the model.</p>
 */
final class DecisionParseException extends Exception {

    private final DecisionParseErrorCode errorCode;
    private final String rawPreview;
    private final Map<String, Object> repairHints;

    public DecisionParseException(DecisionParseErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public DecisionParseException(DecisionParseErrorCode errorCode, String message, String rawPreview) {
        this(errorCode, message, rawPreview, null);
    }

    public DecisionParseException(DecisionParseErrorCode errorCode, String message,
                                   String rawPreview, Map<String, Object> repairHints) {
        super(message);
        this.errorCode = errorCode;
        this.rawPreview = rawPreview;
        this.repairHints = repairHints;
    }

    public DecisionParseErrorCode getErrorCode() {
        return errorCode;
    }

    public String getRawPreview() {
        return rawPreview;
    }

    public Map<String, Object> getRepairHints() {
        return repairHints;
    }

    /**
     * Build a model-facing error message with repair hints.
     */
    public String toModelMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("JSON 解析失败 [").append(errorCode.name()).append("]: ").append(getMessage());
        if (repairHints != null && !repairHints.isEmpty()) {
            sb.append("\n修复提示: ").append(repairHints);
        }
        if (rawPreview != null && !rawPreview.isEmpty()) {
            sb.append("\n原始输出 (截断): ").append(rawPreview);
        }
        return sb.toString();
    }
}
