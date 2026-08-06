package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.service.context.SecretRedactor;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.model.ToolOutputSanitization;

/**
 * Pass-through sanitizer that additionally redacts configured secret values
 * (--secret-env-name values and provider API keys) from tool output before
 * it reaches the ledger, trace, session and any persisted artifact.
 */
public class RedactingToolOutputSanitizer implements ToolOutputSanitizer {

    private final SecretRedactor secretRedactor;

    public RedactingToolOutputSanitizer(SecretRedactor secretRedactor) {
        this.secretRedactor = secretRedactor;
    }

    @Override
    public ToolOutputSanitization sanitize(String toolName, String rawOutput) {
        String redacted = secretRedactor.redact(rawOutput == null ? "" : rawOutput);
        return ToolOutputSanitization.clean(redacted);
    }
}
