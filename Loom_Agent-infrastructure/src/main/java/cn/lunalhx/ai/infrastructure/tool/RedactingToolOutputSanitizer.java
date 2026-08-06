package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.service.context.SanitizationPolicy;
import cn.lunalhx.ai.domain.agent.service.context.SecretRedactor;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.model.ToolOutputSanitization;
import cn.lunalhx.ai.domain.tool.service.InjectionSignalDetector;

import java.util.Set;

/**
 * Default sanitizer: secret redaction via the shared {@link SanitizationPolicy}
 * plus prompt-injection signal detection. Fail-closed: on any exception the
 * output carries no raw data (a fixed minimal error observation is returned
 * by the caller); here a degraded result is produced.
 */
public class RedactingToolOutputSanitizer implements ToolOutputSanitizer {

    private final SecretRedactor secretRedactor;
    private final InjectionSignalDetector detector;
    private final boolean injectionScanEnabled;

    public RedactingToolOutputSanitizer(SecretRedactor secretRedactor) {
        this(secretRedactor, new InjectionSignalDetector(), true);
    }

    public RedactingToolOutputSanitizer(SecretRedactor secretRedactor,
                                        InjectionSignalDetector detector,
                                        boolean injectionScanEnabled) {
        this.secretRedactor = secretRedactor == null ? SecretRedactor.none() : secretRedactor;
        this.detector = detector == null ? new InjectionSignalDetector() : detector;
        this.injectionScanEnabled = injectionScanEnabled;
    }

    @Override
    public ToolOutputSanitization sanitize(String toolName, String rawOutput) {
        try {
            String input = rawOutput == null ? "" : rawOutput;
            String redacted = secretRedactor.redact(input);
            Set<String> signals = injectionScanEnabled
                    ? detector.detect(toolName, redacted) : Set.of();
            boolean redactedSomething = !redacted.equals(input);
            return ToolOutputSanitization.builder()
                    .output(redacted)
                    .injectionDetected(!signals.isEmpty())
                    .matchCount(signals.size())
                    .matchedRuleIds(signals)
                    .redacted(redactedSomething)
                    .redactionCount(redactedSomething ? 1 : 0)
                    .redactionVersion(String.valueOf(SanitizationPolicy.RULES_VERSION))
                    .degraded(false)
                    .build();
        } catch (Exception e) {
            // Fail-closed: never return raw output on sanitizer failure.
            return ToolOutputSanitization.degraded(
                    "tool_error: sanitization_failed - output withheld");
        }
    }
}
