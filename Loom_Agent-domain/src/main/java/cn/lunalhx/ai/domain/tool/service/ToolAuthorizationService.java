package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentStep;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.CallEffectAssessment;
import cn.lunalhx.ai.domain.tool.model.EffectProfile;
import cn.lunalhx.ai.domain.tool.model.ExecutionGrant;
import cn.lunalhx.ai.domain.tool.model.ExecutionGrantRequest;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.GrantLifetime;
import cn.lunalhx.ai.domain.tool.model.NormalizedToolCall;
import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.PermissionDecision;
import cn.lunalhx.ai.domain.tool.model.PermissionGrant;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolInputValidationResult;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The single pre-execution authorization pipeline. Returns decisions only —
 * interactive approval I/O belongs to the caller via {@link ToolApprovalResolver}.
 */
public final class ToolAuthorizationService {
    private final ToolRegistry registry;
    private final ToolCallNormalizer normalizer;
    private final ExecutionGrantValidator executionGrantValidator = new ExecutionGrantValidator();

    public ToolAuthorizationService(ToolRegistry registry, ObjectMapper mapper) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.normalizer = new ToolCallNormalizer(Objects.requireNonNull(mapper, "mapper must not be null"));
    }

    public ToolAuthorizationResult authorize(AgentContext context, ToolCall call,
                                              ToolExecutor.ToolRuntimePolicy runtimePolicy,
                                              PermissionPolicySnapshot policy) {
        String name = call.getName();
        if (context.getCollaborationMode() == CollaborationMode.PLAN
                && !registry.isPlanVisible(name, runtimePolicy.allowedTools())) {
            return reject(call, "plan_mode_denied", "plan_mode_denied", EffectProfile.unknown());
        }
        if (runtimePolicy.allowedTools() != null && !runtimePolicy.allowedTools().contains(name)) {
            return reject(call, "tool_not_allowed", "tool_not_allowed", EffectProfile.unknown());
        }
        if (!registry.contains(name)) {
            return reject(call, "unknown_tool", "unknown_tool", EffectProfile.unknown());
        }
        NormalizedToolCall normalized = normalizer.normalize(call);
        ToolInputValidationResult validation = registry.validateInput(name, normalized.canonicalInput());
        if (!validation.valid()) {
            return reject(call, "invalid_arguments", "invalid_arguments", EffectProfile.unknown());
        }
        if (isRepeated(context, name, normalized.canonicalInput().toString())) {
            return reject(call, "repeated_identical_call", "repeated_identical_call", EffectProfile.unknown());
        }
        call.setInput(normalized.canonicalInput());
        ExecutionProfile profile = runtimePolicy.executionProfile();
        if (!registry.isAvailable(name, profile)) {
            return reject(call, "execution_backend_unavailable", "execution_backend_unavailable", EffectProfile.unknown());
        }
        List<ExecutionGrant> availableExecutionGrants = new ArrayList<>(profile.externalGrants());
        availableExecutionGrants.addAll(context.getExecutionGrants());
        List<ExecutionGrantRequest> executionRequests;
        try {
            executionRequests = executionGrantValidator.requests(name, normalized.canonicalInput(), profile);
        } catch (IllegalArgumentException denied) {
            return reject(call, "execution_grant_denied", "execution_grant_denied", EffectProfile.unknown());
        }
        for (ExecutionGrantRequest request : executionRequests) {
            boolean covered = availableExecutionGrants.stream().anyMatch(grant ->
                    grant.access().includes(request.access())
                            && request.canonicalPath().startsWith(grant.canonicalPath()));
            if (!covered) {
                return ToolAuthorizationResult.needsExecutionGrant(new ToolAuthorizationResult.PendingExecutionGrant(
                        request, call, normalized, profile, List.copyOf(availableExecutionGrants),
                        context.getRunId(),
                        Objects.requireNonNull(policy, "policy must not be null").snapshotDigest(),
                        runtimePolicy, policy));
            }
        }
        return continueAfterExecutionGrants(context, call, normalized, profile,
                executionRequests, availableExecutionGrants, policy);
    }

    /** Resume after the caller supplies an execution grant decision. */
    public ToolAuthorizationResult continueWithExecutionGrant(AgentContext context,
                                                              ToolAuthorizationResult.PendingExecutionGrant pending,
                                                              GrantLifetime lifetime) {
        Objects.requireNonNull(pending, "pending must not be null");
        if (lifetime == null) {
            return reject(pending.call(), "execution_grant_denied", "execution_grant_denied", EffectProfile.unknown());
        }
        ExecutionGrant grant = new ExecutionGrant(pending.request().canonicalPath(),
                pending.request().access(), lifetime);
        List<ExecutionGrant> available = new ArrayList<>(pending.availableGrants());
        available.add(grant);
        if (lifetime != GrantLifetime.ONCE) {
            if (lifetime == GrantLifetime.WORKSPACE) {
                Path workspace = context.getResolvedWorkspace();
                if (workspace == null) {
                    return reject(pending.call(), "execution_grant_denied", "execution_grant_denied",
                            EffectProfile.unknown());
                }
                new WorkspacePermissionGrantStore().appendExecution(workspace, grant);
            }
            if (lifetime == GrantLifetime.SESSION) {
                context.addSessionExecutionGrant(grant);
            } else {
                context.addExecutionGrant(grant);
            }
        }
        List<ExecutionGrantRequest> executionRequests;
        try {
            executionRequests = executionGrantValidator.requests(
                    pending.call().getName(), pending.normalized().canonicalInput(), pending.baseProfile());
        } catch (IllegalArgumentException denied) {
            return reject(pending.call(), "execution_grant_denied", "execution_grant_denied", EffectProfile.unknown());
        }
        for (ExecutionGrantRequest request : executionRequests) {
            boolean covered = available.stream().anyMatch(existing ->
                    existing.access().includes(request.access())
                            && request.canonicalPath().startsWith(existing.canonicalPath()));
            if (!covered) {
                return ToolAuthorizationResult.needsExecutionGrant(new ToolAuthorizationResult.PendingExecutionGrant(
                        request, pending.call(), pending.normalized(), pending.baseProfile(),
                        List.copyOf(available), pending.runId(), pending.snapshotDigest(),
                        pending.runtimePolicy(), pending.policy()));
            }
        }
        return continueAfterExecutionGrants(context, pending.call(), pending.normalized(),
                pending.baseProfile(), executionRequests, available, pending.policy());
    }

    /** Apply a user approval decision for a previously returned ASK outcome. */
    public ToolAuthorizationResult completeApproval(AgentContext context,
                                                    ToolAuthorizationResult.PendingToolApproval pending,
                                                    GrantLifetime lifetime) {
        Objects.requireNonNull(pending, "pending must not be null");
        if (lifetime == null) {
            return reject(pending.call(), "approval_denied", "approval_denied", pending.effectProfile());
        }
        if (pending.decision().perCallOnly() && lifetime != GrantLifetime.ONCE) {
            return reject(pending.call(), "approval_denied", "per_call_only", pending.effectProfile());
        }
        if (lifetime != GrantLifetime.ONCE) {
            PermissionGrant grant = PermissionGrant.issue(
                    pending.normalized().permissionSubject().exactKey(), pending.baseProfile(), lifetime);
            try {
                if (lifetime == GrantLifetime.WORKSPACE) {
                    Path workspace = context.getResolvedWorkspace();
                    if (workspace == null) {
                        return reject(pending.call(), "grant_persistence_failed", "grant_persistence_failed",
                                pending.effectProfile());
                    }
                    new WorkspacePermissionGrantStore().append(workspace, grant);
                }
                context.addPermissionGrant(grant);
            } catch (RuntimeException e) {
                return reject(pending.call(), "grant_persistence_failed", "grant_persistence_failed",
                        pending.effectProfile());
            }
        }
        pending.call().setExecutionProfile(pending.effectiveProfile());
        return ToolAuthorizationResult.authorized(new AuthorizedToolCall(
                pending.call(), pending.normalized(), pending.effectProfile(),
                pending.effectiveProfile(), pending.baseProfile(), pending.decision(),
                pending.runId(), pending.snapshotDigest()));
    }

    private ToolAuthorizationResult continueAfterExecutionGrants(
            AgentContext context,
            ToolCall call,
            NormalizedToolCall normalized,
            ExecutionProfile profile,
            List<ExecutionGrantRequest> executionRequests,
            List<ExecutionGrant> availableExecutionGrants,
            PermissionPolicySnapshot policy) {
        try {
            executionGrantValidator.validate(executionRequests, availableExecutionGrants);
        } catch (IllegalArgumentException denied) {
            return reject(call, "execution_grant_denied", "execution_grant_denied", EffectProfile.unknown());
        }
        List<ExecutionGrant> effectiveGrants = new ArrayList<>(profile.externalGrants());
        effectiveGrants.addAll(executionRequests.stream()
                .map(request -> new ExecutionGrant(request.canonicalPath(), request.access(), GrantLifetime.ONCE))
                .toList());
        ExecutionProfile effectiveProfile = profile.withExternalGrants(effectiveGrants);
        call.setExecutionProfile(effectiveProfile);
        CallEffectAssessment effect = registry.assessEffect(call.getName(), call, effectiveProfile);
        if (!effect.trusted() || !profile.allows(effect.profile())) {
            return reject(call, "execution_profile_denied", "execution_profile_denied", effect.profile());
        }
        PermissionDecision decision = Objects.requireNonNull(policy, "policy must not be null")
                .evaluate(normalized.permissionSubject());
        if (decision.action() == PermissionAction.DENY) {
            return reject(call, "permission_denied", decision.reasonCode(), effect.profile());
        }
        if (decision.action() == PermissionAction.ASK && !hasReusableGrant(context, normalized, profile, decision)) {
            return ToolAuthorizationResult.needsApproval(new ToolAuthorizationResult.PendingToolApproval(
                    display(call, normalized, profile), decision, call, normalized, effect.profile(),
                    effectiveProfile, profile, context.getRunId(), policy.snapshotDigest()));
        }
        return ToolAuthorizationResult.authorized(new AuthorizedToolCall(call, normalized,
                effect.profile(), effectiveProfile, profile, decision, context.getRunId(), policy.snapshotDigest()));
    }

    private ToolAuthorizationResult reject(ToolCall call, String errorCode, String event,
                                           EffectProfile profile) {
        String message = "approval_denied".equals(errorCode)
                ? "error: approval denied" : "error: " + errorCode;
        ToolResult result = ToolResult.failure(errorCode, message, 0L);
        result.setToolStatus("rejected");
        result.setToolErrorCode(errorCode);
        result.setSecurityEventType(event);
        result.setEffectProfile(profile);
        result.setReadOnly(profile != null && profile.isReadOnly());
        result.setAffectedPaths(List.of());
        result.setWorkspaceChanged(false);
        result.setDiffSummary(List.of());
        return ToolAuthorizationResult.rejected(result);
    }

    private AuthorizationDisplay display(ToolCall call, NormalizedToolCall normalized,
                                         ExecutionProfile profile) {
        Map<String, Object> safe = ApprovalDisplay.summarize(normalized.canonicalInput());
        return new AuthorizationDisplay(call.getName(), safe.toString(),
                normalized.permissionSubject().shellUnits(),
                call.getWorkspaceRoot() == null ? "" : call.getWorkspaceRoot().toString(), profile);
    }

    private boolean isRepeated(AgentContext context, String name, String input) {
        List<AgentStep> history = context.getHistory();
        if (history == null || history.size() < 2) {
            return false;
        }
        return history.subList(history.size() - 2, history.size()).stream().allMatch(step ->
                name.equals(step.getTool()) && Objects.equals(input, step.getInput()));
    }

    private boolean hasReusableGrant(AgentContext context, NormalizedToolCall normalized,
                                     ExecutionProfile profile, PermissionDecision decision) {
        if (decision.perCallOnly() || decision.sourceIds().contains("builtin")) {
            return false;
        }
        return context.getPermissionGrants().stream().anyMatch(grant ->
                grant.matches(normalized.permissionSubject().exactKey(), profile));
    }
}
