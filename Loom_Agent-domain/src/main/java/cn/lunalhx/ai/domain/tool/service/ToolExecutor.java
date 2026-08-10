package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ApprovalRequirement;
import cn.lunalhx.ai.domain.tool.model.CallEffectAssessment;
import cn.lunalhx.ai.domain.tool.model.EffectProfile;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.OutboundDisclosure;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolEffect;
import cn.lunalhx.ai.domain.tool.model.ToolOutputSanitization;
import cn.lunalhx.ai.domain.tool.model.ToolResult;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Execution service of the tool chain. Input governance lives in
 * {@link ToolInputGate}; this class repeats the immutable Plan boundary as a
 * last pre-execution check so direct callers cannot bypass it.
 */
public class ToolExecutor {

    public static final int MAX_TOOL_OUTPUT = 4000;

    /** Runtime policy for one call, including the frozen collaboration mode. */
    public record ToolRuntimePolicy(Set<String> allowedTools,
                                    CollaborationMode mode,
                                    ApprovalPolicy approvalPolicy,
                                    int depth,
                                    int maxDepth,
                                    ExecutionProfile executionProfile) {
        public ToolRuntimePolicy {
            mode = Objects.requireNonNull(mode, "collaboration mode must not be null");
            approvalPolicy = Objects.requireNonNull(approvalPolicy,
                    "approval policy must not be null");
            executionProfile = ExecutionProfile.forRun(mode, depth > 0);
        }

        public static ToolRuntimePolicy root(Set<String> allowedTools,
                                             CollaborationMode mode,
                                             ApprovalPolicy policy) {
            return new ToolRuntimePolicy(allowedTools, mode, policy, 0, 1,
                    ExecutionProfile.forRun(mode, false));
        }

        public static ToolRuntimePolicy delegateChild(Set<String> allowedTools,
                                                      CollaborationMode mode) {
            return new ToolRuntimePolicy(allowedTools, mode, ApprovalPolicy.NEVER, 1, 1,
                    ExecutionProfile.forRun(mode, true));
        }
    }

    /** CLI-facing approval prompt. Receives only redacted/summarized args. */
    public interface ApprovalPrompt {
        boolean ask(String toolName, com.fasterxml.jackson.databind.JsonNode args);
    }

    public enum ApprovalPolicy { ASK, AUTO, NEVER }

    private static final String SANITIZE_FAILED_OBSERVATION =
            "tool_error: sanitization_failed - output withheld";
    private static final String PLAN_DENIED_MESSAGE =
            "error: tool call is not authorized by Plan Mode";

    private final ToolRegistry registry;
    private final ToolOutputSanitizer sanitizer;

    public ToolExecutor(ToolRegistry registry, ToolOutputSanitizer sanitizer) {
        this.registry = registry;
        this.sanitizer = sanitizer;
    }

    public ToolExecutor(ToolRegistry registry) {
        this(registry, null);
    }

    public ToolRegistry registry() {
        return registry;
    }

    /** Execute one accepted tool call; rejected calls never reach here. */
    public ToolResult execute(AgentContext context, ToolCall call) {
        String name = call.getName();
        CollaborationMode mode = context.getCollaborationMode();
        boolean delegateRun = context.getParentRunId() != null;
        ExecutionProfile executionProfile = ExecutionProfile.forRun(mode, delegateRun);
        CallEffectAssessment assessment = registry.assessEffect(name, call, executionProfile);
        EffectProfile effectProfile = assessment.profile();

        boolean planDenied = mode == CollaborationMode.PLAN
                && (!assessment.trusted()
                || !registry.isPlanVisible(name, context.getAllowedTools())
                || !executionProfile.allows(effectProfile));
        boolean delegateDenied = delegateRun
                && (!assessment.trusted() || !executionProfile.allows(effectProfile));
        if (planDenied || delegateDenied) {
            ToolResult result = ToolResult.failure("plan_mode_denied", PLAN_DENIED_MESSAGE, 0L);
            result.setToolStatus("rejected");
            result.setToolErrorCode("plan_mode_denied");
            result.setSecurityEventType("plan_mode_denied");
            applyEffectMetadata(result, effectProfile, registry.approvalRequirement(name));
            result.setAffectedPaths(java.util.List.of());
            result.setWorkspaceChanged(false);
            result.setDiffSummary(java.util.List.of());
            result.setObservation(PLAN_DENIED_MESSAGE);
            context.setToolResult(result);
            return result;
        }

        call.setEffectProfile(effectProfile);
        call.setApprovalRequired(registry.approvalRequirement(name).required());
        Path root = call.getWorkspaceRoot();
        boolean inspectWorkspace = requiresWorkspaceInspection(effectProfile);
        Map<String, String> before = inspectWorkspace
                ? WorkspaceFingerprint.snapshot(root) : Map.of();
        Map<String, String> after = before;
        try {
            ToolResult result = registry.call(call);
            after = inspectWorkspace ? WorkspaceFingerprint.snapshot(root) : before;
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
            applyMetadata(result, toolStatus, toolErrorCode, effectProfile,
                    registry.approvalRequirement(name), diff, workspaceChanged, root);
            context.setToolResult(result);
            return result;
        } catch (Exception e) {
            after = inspectWorkspace ? WorkspaceFingerprint.snapshot(root) : before;
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
            applyEffectMetadata(result, effectProfile, registry.approvalRequirement(name));
            result.setAffectedPaths(diff.affectedPaths());
            result.setWorkspaceChanged(workspaceChanged);
            result.setDiffSummary(diff.diffSummary());
            applySafeOutput(result, name);
            context.setToolResult(result);
            return result;
        }
    }

    private boolean requiresWorkspaceInspection(EffectProfile profile) {
        if (profile == null || !profile.complete()
                || profile.outboundDisclosure() != OutboundDisclosure.NONE) {
            return true;
        }
        return profile.effects().stream().anyMatch(effect -> effect != ToolEffect.REPOSITORY_READ);
    }

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
                               EffectProfile effectProfile, ApprovalRequirement approvalRequirement,
                               WorkspaceFingerprint.DiffResult diff,
                               boolean workspaceChanged, Path root) {
        result.setToolStatus(toolStatus);
        result.setToolErrorCode(toolErrorCode);
        applyEffectMetadata(result, effectProfile, approvalRequirement);
        result.setAffectedPaths(diff.affectedPaths());
        result.setWorkspaceChanged(workspaceChanged);
        result.setDiffSummary(diff.diffSummary());
        if (root != null) {
            result.setWorkspaceFingerprint(WorkspaceFingerprint.stableFingerprint(root));
        }
    }

    private void applyEffectMetadata(ToolResult result, EffectProfile effectProfile,
                                     ApprovalRequirement approvalRequirement) {
        result.setEffectProfile(effectProfile);
        result.setApprovalRequired(approvalRequirement.required());
        result.setReadOnly(effectProfile != null && effectProfile.isReadOnly());
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
