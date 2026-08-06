package cn.lunalhx.ai.domain.tool.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Result contract of one sanitization pass. Expresses the output, whether
 * redaction actually happened, hit counts, matched rule ids, suspected
 * injection signals and a fail-closed degradation state. The absence of an
 * injection signal is never named "clean" — it is "no injection signal".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolOutputSanitization {

    private String output;
    private boolean injectionDetected;
    private int matchCount;
    private Set<String> matchedRuleIds;
    private boolean redacted;
    private int redactionCount;
    private String redactionVersion;
    private boolean degraded;

    public static ToolOutputSanitization clean(String output) {
        return ToolOutputSanitization.builder()
                .output(output)
                .injectionDetected(false)
                .matchCount(0)
                .matchedRuleIds(Set.of())
                .redacted(false)
                .redactionCount(0)
                .redactionVersion(null)
                .degraded(false)
                .build();
    }

    /** Fail-closed marker: output must not carry raw data. */
    public static ToolOutputSanitization degraded(String fallbackOutput) {
        return ToolOutputSanitization.builder()
                .output(fallbackOutput)
                .injectionDetected(false)
                .matchCount(0)
                .matchedRuleIds(Set.of())
                .redacted(true)
                .redactionCount(0)
                .redactionVersion(null)
                .degraded(true)
                .build();
    }
}
