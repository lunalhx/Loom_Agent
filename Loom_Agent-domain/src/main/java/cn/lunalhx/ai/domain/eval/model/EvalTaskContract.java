package cn.lunalhx.ai.domain.eval.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Offline task contract for the loom-code evaluation suite. Each task is
 * fully self-contained: fixture workspace + deterministic fake model script
 * + expected artifact + verifier.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalTaskContract {

    private String taskId;
    private String prompt;
    private String fixtureWorkspace;
    private List<String> allowedTools;
    private Integer stepBudget;
    private String expectedArtifact;
    private List<String> verifier;
    private String category;
}
