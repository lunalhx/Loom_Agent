package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.DelegateProvenance;
import cn.lunalhx.ai.domain.agent.model.entity.DelegateResult;
import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.EvidenceRevalidation;
import cn.lunalhx.ai.domain.tool.model.EffectProfile;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.OutboundDisclosure;
import cn.lunalhx.ai.domain.tool.model.ToolEffect;
import cn.lunalhx.ai.domain.tool.model.ToolOutputSanitization;
import cn.lunalhx.ai.domain.tool.model.ToolResult;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Execution service of the tool chain. Input governance lives in
 * {@link ToolAuthorizationService}; it accepts only a Runtime-minted
 * {@link AuthorizedToolCall} and does not re-evaluate authorization.
 */
public class ToolExecutor {

    public static final int MAX_TOOL_OUTPUT = 4000;

    /** Runtime policy for one call, including the frozen collaboration mode. */
    public record ToolRuntimePolicy(Set<String> allowedTools,
                                    CollaborationMode mode,
                                    int depth,
                                    int maxDepth,
                                    ExecutionProfile executionProfile) {
        public ToolRuntimePolicy {
            mode = Objects.requireNonNull(mode, "collaboration mode must not be null");
            executionProfile = Objects.requireNonNull(executionProfile,
                    "executionProfile must not be null");
        }

        public static ToolRuntimePolicy root(Set<String> allowedTools,
                                             CollaborationMode mode) {
            return new ToolRuntimePolicy(allowedTools, mode, 0, 1,
                    ExecutionProfile.forRun(mode, false));
        }

        public static ToolRuntimePolicy delegateChild(Set<String> allowedTools,
                                                      CollaborationMode mode) {
            return new ToolRuntimePolicy(allowedTools, mode, 1, 1,
                    ExecutionProfile.forRun(mode, true));
        }
    }

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

    public ToolRegistry registry() {
        return registry;
    }

    /** Execute one accepted call after verifying its frozen run and snapshot binding. */
    public ToolResult execute(AgentContext context, AuthorizedToolCall authorized) {
        if (authorized == null || !Objects.equals(context.getRunId(), authorized.runId())
                || context.getPermissionPolicySnapshot() == null
                || !Objects.equals(context.getPermissionPolicySnapshot().snapshotDigest(), authorized.snapshotDigest())
                || !Objects.equals(context.getExecutionProfile(), authorized.executionProfile())) {
            return unauthorized(context);
        }
        var call = authorized.rawCall();
        String name = call.getName();
        EffectProfile effectProfile = authorized.effectProfile();
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

            capturePlanEvidence(context, result);
            result.clearTransientEvidence();
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
            applyMetadata(result, toolStatus, toolErrorCode, effectProfile, diff, workspaceChanged, root);
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
            applyEffectMetadata(result, effectProfile);
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

    private void capturePlanEvidence(AgentContext context, ToolResult result) {
        if (context.getCollaborationMode() != CollaborationMode.PLAN || result == null) {
            return;
        }
        if (result.isSuccess() && result.getEvidenceCandidate() != null) {
            EvidenceReceipt receipt = EvidenceReceipt.from(
                    result.getEvidenceCandidate(), context.getRunId(), context.getRootRunId());
            if (receipt != null) {
                context.recordEvidence(receipt);
            }
        }
        DelegateResult delegateResult = result.getDelegateResult();
        if (delegateResult == null || !delegateResult.isSuccessful()) {
            return;
        }
        foldDelegateEvidence(context, delegateResult);
    }

    /** Fold only receipts that the root can prove came from its bounded child. */
    private void foldDelegateEvidence(AgentContext context, DelegateResult result) {
        DelegateProvenance provenance = result.getProvenance();
        if (!authorizedChild(context, provenance)) {
            return;
        }
        for (EvidenceReceipt receipt : result.safeEvidenceReceipts()) {
            if (authorizedReceipt(context, provenance, receipt)) {
                context.recordEvidence(receipt);
            }
        }
    }

    private boolean authorizedChild(AgentContext context, DelegateProvenance provenance) {
        if (provenance == null || provenance.getRunId() == null
                || provenance.getParentRunId() == null
                || !Objects.equals(context.getRunId(), provenance.getParentRunId())
                || !Objects.equals(rootRunId(context), provenance.getRootRunId())
                || !Objects.equals(context.getSessionId(), provenance.getSessionId())
                || provenance.getModeSnapshot() != context.getCollaborationMode()
                || !sameWorkspace(context.getResolvedWorkspace(), provenance.getWorkspaceRoot())) {
            return false;
        }
        AgentRuntimeProperties properties =
                context.runtimeProperties(new AgentRuntimeProperties());
        int maxDepth = properties.getSubAgentMaxDepth() == null
                ? 1 : properties.getSubAgentMaxDepth();
        return provenance.getDepth() != null
                && provenance.getDepth() == context.getAgentDepth() + 1
                && provenance.getDepth() <= maxDepth;
    }

    private boolean authorizedReceipt(AgentContext context, DelegateProvenance provenance,
                                     EvidenceReceipt receipt) {
        EvidenceRevalidation rule = receipt == null ? null : receipt.getRevalidation();
        if (receipt == null || !receipt.isRevalidatable()
                || !Objects.equals(provenance.getRunId(), receipt.getSourceRunId())
                || !Objects.equals(rootRunId(context), receipt.getRootRunId())
                || !withinWorkspace(context.getResolvedWorkspace(), rule.getRepositoryRelativePath())) {
            return false;
        }
        String toolName = rule.getObservationType().toolName();
        if (context.getAllowedTools() != null && !context.getAllowedTools().contains(toolName)) {
            return false;
        }
        return registry.isPlanVisible(toolName, context.getAllowedTools());
    }

    private boolean withinWorkspace(Path root, String relativePath) {
        if (root == null || relativePath == null || relativePath.isBlank()) {
            return false;
        }
        try {
            Path relative = Path.of(relativePath);
            if (relative.isAbsolute()) {
                return false;
            }
            Path realRoot = root.toRealPath();
            Path candidate = realRoot.resolve(relative).normalize();
            if (!candidate.startsWith(realRoot)) {
                return false;
            }
            return candidate.toRealPath().startsWith(realRoot);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean sameWorkspace(Path root, String childRoot) {
        if (root == null || childRoot == null || childRoot.isBlank()) {
            return false;
        }
        try {
            return root.toRealPath().equals(Path.of(childRoot).toRealPath());
        } catch (Exception e) {
            return false;
        }
    }

    private String rootRunId(AgentContext context) {
        return context.getRootRunId() == null || context.getRootRunId().isBlank()
                ? context.getRunId() : context.getRootRunId();
    }

    private ToolResult unauthorized(AgentContext context) {
        ToolResult result = ToolResult.failure("unauthorized_tool_call",
                "error: executor requires an authorized tool call", 0L);
        result.setToolStatus("rejected");
        result.setToolErrorCode("unauthorized_tool_call");
        result.setSecurityEventType("unauthorized_tool_call");
        context.setToolResult(result);
        return result;
    }

    private void applyMetadata(ToolResult result, String toolStatus, String toolErrorCode,
                               EffectProfile effectProfile,
                               WorkspaceFingerprint.DiffResult diff,
                               boolean workspaceChanged, Path root) {
        result.setToolStatus(toolStatus);
        result.setToolErrorCode(toolErrorCode);
        applyEffectMetadata(result, effectProfile);
        result.setAffectedPaths(diff.affectedPaths());
        result.setWorkspaceChanged(workspaceChanged);
        result.setDiffSummary(diff.diffSummary());
        if (root != null) {
            result.setWorkspaceFingerprint(WorkspaceFingerprint.stableFingerprint(root));
        }
    }

    private void applyEffectMetadata(ToolResult result, EffectProfile effectProfile) {
        result.setEffectProfile(effectProfile);
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
