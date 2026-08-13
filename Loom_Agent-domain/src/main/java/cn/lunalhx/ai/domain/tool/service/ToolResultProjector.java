package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.DelegateProvenance;
import cn.lunalhx.ai.domain.agent.model.entity.DelegateResult;
import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.EffectProfile;
import cn.lunalhx.ai.domain.tool.model.EvidenceRevalidation;
import cn.lunalhx.ai.domain.tool.model.OutboundDisclosure;
import cn.lunalhx.ai.domain.tool.model.ShellExecutionResult;
import cn.lunalhx.ai.domain.tool.model.ToolEffect;
import cn.lunalhx.ai.domain.tool.model.ToolOutputSanitization;
import cn.lunalhx.ai.domain.tool.model.ToolResult;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic projection of an adapter {@link ToolResult} into Agent-visible
 * state: sanitization, workspace diff metadata, evidence capture.
 */
public final class ToolResultProjector {
    public static final int MAX_TOOL_OUTPUT = ToolExecutor.MAX_TOOL_OUTPUT;

    private static final String SANITIZE_FAILED_OBSERVATION =
            "tool_error: sanitization_failed - output withheld";

    private final ToolOutputSanitizer sanitizer;
    private final ToolRegistry registry;

    public ToolResultProjector(ToolOutputSanitizer sanitizer) {
        this(sanitizer, null);
    }

    public ToolResultProjector(ToolOutputSanitizer sanitizer, ToolRegistry registry) {
        this.sanitizer = sanitizer;
        this.registry = registry;
    }

    public ToolResult project(AgentContext context, AuthorizedToolCall authorized,
                              ToolResult adapterResult, Exception failure) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(authorized, "authorized must not be null");
        var call = authorized.executionCall();
        String name = call.getName();
        EffectProfile effectProfile = authorized.effectProfile();
        Path root = call.getWorkspaceRoot();
        boolean inspectWorkspace = requiresWorkspaceInspection(effectProfile);

        if (failure != null) {
            Map<String, String> after = inspectWorkspace && root != null
                    ? RepositoryStateTracker.snapshot(root) : Map.of();
            RepositoryStateTracker.DiffResult diff = RepositoryStateTracker.diff(Map.of(), after);
            boolean workspaceChanged = !diff.affectedPaths().isEmpty();
            String securityEvent = failure.getMessage() != null
                    && failure.getMessage().contains("path escapes workspace")
                    ? "path_escape" : "";
            ToolResult result = ToolResult.failure(
                    workspaceChanged ? "tool_partial_success" : "tool_failed",
                    "error: tool " + name + " failed: " + failure.getMessage(), 0L);
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

        Objects.requireNonNull(adapterResult, "adapterResult must not be null");
        Map<String, String> before = Map.of();
        Map<String, String> after = inspectWorkspace && root != null
                ? RepositoryStateTracker.snapshot(root) : before;
        // Diff against empty before when caller did not supply a pre-image; ToolExecutor
        // supplies before/after via projectWithDiff when inspection is required.
        RepositoryStateTracker.DiffResult diff = RepositoryStateTracker.diff(before, after);
        return finishProjection(context, authorized, adapterResult, diff, root);
    }

    public ToolResult projectWithDiff(AgentContext context, AuthorizedToolCall authorized,
                                      ToolResult adapterResult,
                                      Map<String, String> before,
                                      Map<String, String> after) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(authorized, "authorized must not be null");
        Objects.requireNonNull(adapterResult, "adapterResult must not be null");
        Path root = authorized.executionCall().getWorkspaceRoot();
        RepositoryStateTracker.DiffResult diff = RepositoryStateTracker.diff(
                before == null ? Map.of() : before,
                after == null ? Map.of() : after);
        return finishProjection(context, authorized, adapterResult, diff, root);
    }

    public ToolResult projectFailureWithDiff(AgentContext context, AuthorizedToolCall authorized,
                                             Exception failure,
                                             Map<String, String> before,
                                             Map<String, String> after) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(authorized, "authorized must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        String name = authorized.toolName();
        EffectProfile effectProfile = authorized.effectProfile();
        Path root = authorized.executionCall().getWorkspaceRoot();
        RepositoryStateTracker.DiffResult diff = RepositoryStateTracker.diff(
                before == null ? Map.of() : before,
                after == null ? Map.of() : after);
        boolean workspaceChanged = !diff.affectedPaths().isEmpty();
        String securityEvent = failure.getMessage() != null
                && failure.getMessage().contains("path escapes workspace")
                ? "path_escape" : "";
        ToolResult result = ToolResult.failure(
                workspaceChanged ? "tool_partial_success" : "tool_failed",
                "error: tool " + name + " failed: " + failure.getMessage(), 0L);
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

    private ToolResult finishProjection(AgentContext context, AuthorizedToolCall authorized,
                                        ToolResult result,
                                        RepositoryStateTracker.DiffResult diff,
                                        Path root) {
        String name = authorized.toolName();
        EffectProfile effectProfile = authorized.effectProfile();
        boolean workspaceChanged = !diff.affectedPaths().isEmpty();

        capturePlanEvidence(context, result);
        result.clearTransientEvidence();
        applySafeOutput(result, name);

        String toolStatus = "ok";
        String toolErrorCode = "";
        if ("run_shell".equals(name)) {
            ShellExecutionResult shell = result.getShellExecutionResult();
            int exitCode = shell == null ? -1 : shell.exitCode();
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
    }

    boolean requiresWorkspaceInspection(EffectProfile profile) {
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
        if (result.isSuccess()) {
            for (var candidate : result.evidenceCandidatesSafe()) {
                EvidenceReceipt receipt = EvidenceReceipt.from(candidate, context.getRunId(), context.getRootRunId());
                if (receipt != null) {
                    context.recordEvidence(receipt);
                }
            }
        }
        DelegateResult delegateResult = result.getDelegateResult();
        if (delegateResult == null || !delegateResult.isSuccessful()) {
            return;
        }
        foldDelegateEvidence(context, delegateResult);
    }

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
        return registry != null && registry.isPlanVisible(toolName, context.getAllowedTools());
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

    private void applyMetadata(ToolResult result, String toolStatus, String toolErrorCode,
                               EffectProfile effectProfile,
                               RepositoryStateTracker.DiffResult diff,
                               boolean workspaceChanged, Path root) {
        result.setToolStatus(toolStatus);
        result.setToolErrorCode(toolErrorCode);
        applyEffectMetadata(result, effectProfile);
        result.setAffectedPaths(diff.affectedPaths());
        result.setWorkspaceChanged(workspaceChanged);
        result.setDiffSummary(diff.diffSummary());
        if (root != null) {
            result.setWorkspaceFingerprint(RepositoryStateTracker.stableFingerprint(root));
        }
    }

    private void applyEffectMetadata(ToolResult result, EffectProfile effectProfile) {
        result.setEffectProfile(effectProfile);
        result.setReadOnly(effectProfile != null && effectProfile.isReadOnly());
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
