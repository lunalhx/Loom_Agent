package cn.lunalhx.ai.domain.tool.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

/**
 * Result of analyzing a shell command string for execution strategy and risk.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShellCommandAnalysis {

    private ShellExecutionMode executionMode;
    /**
     * Tokenized args for ProcessBuilder (only populated for SIMPLE_EXEC).
     */
    private List<String> tokens;
    /**
     * Original raw command (for SHELL_EXEC, passed to shell interpreter).
     */
    private String rawCommand;
    /**
     * Detected shell syntax features.
     */
    private Set<ShellFeature> features;
    /**
     * Best-effort primary command name (first executable).
     */
    private String primaryCommand;
    /**
     * Human-readable risk tags for UI and logging.
     */
    private List<String> riskTags;
    /**
     * If true, the command is hard-denied regardless of execution mode.
     */
    private boolean hardDenied;
    /**
     * Reason for hard denial (populated when hardDenied is true).
     */
    private String hardDenyReason;
}
