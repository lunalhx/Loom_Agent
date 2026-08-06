package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolOutputSanitization;
import cn.lunalhx.ai.domain.tool.model.ToolResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Execution service of the tool chain: the only entry point that calls the
 * {@link ToolRegistry}. Input governance (allowlist, existence, schema,
 * repeated-call, readOnly/approval) lives in {@link ToolInputGate}.
 *
 * <p>Security boundary: the raw result returned by {@code registry.call} is
 * handled restrictively inside this class — output is sanitized BEFORE it is
 * clipped and BEFORE it is written to {@code context.toolResult}. A sanitizer
 * failure is fail-closed: a fixed minimal error observation is written and raw
 * output never reaches context, memory, checkpoint or trace.
 *
 * <p>This class never writes prompt/history/ledger/checkpoint; it produces one
 * execution result plus metadata per call.
 */
public class ToolExecutor {

    public static final int MAX_TOOL_OUTPUT = 4000;

    /** Runtime policy for a single tool call (shared with the input gate). */
    public record ToolRuntimePolicy(Set<String> allowedTools,
                                    boolean readOnly,
                                    ApprovalPolicy approvalPolicy,
                                    int depth,
                                    int maxDepth) {
        public static ToolRuntimePolicy root(Set<String> allowedTools, ApprovalPolicy policy) {
            return new ToolRuntimePolicy(allowedTools, false, policy, 0, 1);
        }

        public static ToolRuntimePolicy delegateChild(Set<String> allowedTools) {
            return new ToolRuntimePolicy(allowedTools, true, ApprovalPolicy.NEVER, 1, 1);
        }
    }

    /** CLI-facing approval prompt. Receives only redacted/summarized args. */
    public interface ApprovalPrompt {
        boolean ask(String toolName, com.fasterxml.jackson.databind.JsonNode args);
    }

    public enum ApprovalPolicy { ASK, AUTO, NEVER }

    private static final String SANITIZE_FAILED_OBSERVATION =
            "tool_error: sanitization_failed - output withheld";

    private final ToolRegistry registry;
    private final ToolOutputSanitizer sanitizer;

    public ToolExecutor(ToolRegistry registry, ToolOutputSanitizer sanitizer) {
        this.registry = registry;
        this.sanitizer = sanitizer;
    }

    public ToolExecutor(ToolRegistry registry) {
        this(registry, null);
    }

    /** The registry backing this executor (input gate construction). */
    public ToolRegistry registry() {
        return registry;
    }

    /** Execute one accepted tool call. Tool step counting is the caller's
     *  responsibility (the execute node); rejected calls never reach here. */
    public ToolResult execute(AgentContext context, ToolCall call) {
        String name = call.getName();
        Path root = call.getWorkspaceRoot();
        boolean risky = registry.isRisky(name);

        // workspace snapshot before risky tools
        Map<String, String> before = risky ? WorkspaceFingerprint.snapshot(root) : Map.of();
        Map<String, String> after = before;
        try {
            ToolResult result = registry.call(call);
            after = risky ? WorkspaceFingerprint.snapshot(root) : before;
            WorkspaceFingerprint.DiffResult diff = WorkspaceFingerprint.diff(before, after);
            boolean workspaceChanged = !diff.affectedPaths().isEmpty();

            applySafeOutput(result, name);

            String toolStatus = "ok";
            String toolErrorCode = "";
            if ("run_shell".equals(name)) {
                int exitCode = parseExitCode(result.getObservation());
                if (exitCode != 0 && workspaceChanged) {
                    toolStatus = "partial_success";
                    toolErrorCode = "tool_partial_success";
                } else if (exitCode != 0) {
                    toolStatus = "error";
                    toolErrorCode = "tool_failed";
                }
            }
            applyMetadata(result, toolStatus, toolErrorCode, risky, diff, workspaceChanged, root);
            context.setToolResult(result);
            return result;
        } catch (Exception e) {
            after = risky ? WorkspaceFingerprint.snapshot(root) : before;
            WorkspaceFingerprint.DiffResult diff = WorkspaceFingerprint.diff(before, after);
            boolean workspaceChanged = !diff.affectedPaths().isEmpty();
            String securityEvent = e.getMessage() != null && e.getMessage().contains("path escapes workspace")
                    ? "path_escape" : "";
            ToolResult result = ToolResult.failure(
                    workspaceChanged ? "tool_partial_success" : "tool_failed",
                    "error: tool " + name + " failed: " + e.getMessage(), 0L);
            result.setToolStatus(workspaceChanged ? "partial_success" : "error");
            result.setToolErrorCode(workspaceChanged ? "tool_partial_success" : "tool_failed");
            result.setSecurityEventType(securityEvent);
            result.setRiskLevel(risky ? "high" : "low");
            result.setReadOnly(!risky);
            result.setAffectedPaths(diff.affectedPaths());
            result.setWorkspaceChanged(workspaceChanged);
            result.setDiffSummary(diff.diffSummary());
            // Exception messages may embed secrets — sanitize before clip.
            applySafeOutput(result, name);
            context.setToolResult(result);
            return result;
        }
    }

    /** Sanitize-then-clip; fail-closed on sanitizer failure. */
    private void applySafeOutput(ToolResult result, String toolName) {
        String rawObservation = result.getObservation() == null ? "" : result.getObservation();
        ToolOutputSanitization sanitization;
        try {
            sanitization = sanitizer == null
                    ? ToolOutputSanitization.clean(rawObservation)
                    : sanitizer.sanitize(toolName, rawObservation);
        } catch (Exception e) {
            sanitization = ToolOutputSanitization.degraded(SANITIZE_FAILED_OBSERVATION);
        }
        if (sanitization.isDegraded()) {
            result.setObservation(SANITIZE_FAILED_OBSERVATION);
            result.setTruncated(false);
            return;
        }
        String clipped = clip(sanitization.getOutput());
        result.setObservation(clipped);
        result.setTruncated(clipped.length() < sanitization.getOutput().length());
    }

    private void applyMetadata(ToolResult result, String toolStatus, String toolErrorCode,
                               boolean risky, WorkspaceFingerprint.DiffResult diff,
                               boolean workspaceChanged, Path root) {
        result.setToolStatus(toolStatus);
        result.setToolErrorCode(toolErrorCode);
        result.setRiskLevel(risky ? "high" : "low");
        result.setReadOnly(!risky);
        result.setAffectedPaths(diff.affectedPaths());
        result.setWorkspaceChanged(workspaceChanged);
        result.setDiffSummary(diff.diffSummary());
        if (root != null) {
            result.setWorkspaceFingerprint(WorkspaceFingerprint.stableFingerprint(root));
        }
    }

    private int parseExitCode(String content) {
        if (content == null) {
            return 0;
        }
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("exit_code:\\s*(-?\\d+)").matcher(content);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    private String clip(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_TOOL_OUTPUT) {
            return text;
        }
        return text.substring(0, MAX_TOOL_OUTPUT)
                + "\n...[truncated " + (text.length() - MAX_TOOL_OUTPUT) + " chars]";
    }
}
