package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import java.util.List;

/** Safe, ephemeral content rendered by permission prompts. */
public record AuthorizationDisplay(String toolName, String normalizedSummary,
                                   List<String> executableUnits, String workspace,
                                   ExecutionProfile profile) {
    public AuthorizationDisplay {
        executableUnits = executableUnits == null ? List.of() : List.copyOf(executableUnits);
    }
}
