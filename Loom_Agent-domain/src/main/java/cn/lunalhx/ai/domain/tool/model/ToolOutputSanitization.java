package cn.lunalhx.ai.domain.tool.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolOutputSanitization {

    private String output;
    private boolean injectionDetected;
    private int matchCount;
    private Set<String> matchedRuleIds;

    public static ToolOutputSanitization clean(String output) {
        return ToolOutputSanitization.builder()
                .output(output)
                .injectionDetected(false)
                .matchCount(0)
                .matchedRuleIds(Set.of())
                .build();
    }

}
