package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentStep;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.CallEffectAssessment;
import cn.lunalhx.ai.domain.tool.model.EffectProfile;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.GrantLifetime;
import cn.lunalhx.ai.domain.tool.model.NormalizedToolCall;
import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.PermissionDecision;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;
import cn.lunalhx.ai.domain.tool.model.PermissionGrant;
import cn.lunalhx.ai.domain.tool.model.ExecutionGrant;
import cn.lunalhx.ai.domain.tool.model.ExecutionGrantRequest;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolInputValidationResult;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.file.Path;
import java.util.ArrayList;

/** The single pre-execution authorization pipeline. */
public final class ToolAuthorizationService {
    private final ToolRegistry registry;
    private final ToolCallNormalizer normalizer;
    private final PermissionPrompt prompt;
    private final ExecutionGrantValidator executionGrantValidator = new ExecutionGrantValidator();

    public ToolAuthorizationService(ToolRegistry registry, ObjectMapper mapper, PermissionPrompt prompt) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.normalizer = new ToolCallNormalizer(Objects.requireNonNull(mapper, "mapper must not be null"));
        this.prompt = prompt;
    }

    public ToolAuthorizationResult authorize(AgentContext context, ToolCall call,
                                              ToolExecutor.ToolRuntimePolicy runtimePolicy,
                                              PermissionPolicySnapshot policy) {
        String name = call.getName();
        if (runtimePolicy.allowedTools() != null && !runtimePolicy.allowedTools().contains(name)) {
            return reject(call, "tool_not_allowed", "tool_not_allowed", EffectProfile.unknown());
        }
        if (!registry.contains(name)) {
            return reject(call, "unknown_tool", "unknown_tool", EffectProfile.unknown());
        }
        if (context.getCollaborationMode() == CollaborationMode.PLAN
                && !registry.isPlanVisible(name, runtimePolicy.allowedTools())) {
            return reject(call, "plan_mode_denied", "plan_mode_denied", EffectProfile.unknown());
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
        List<ExecutionGrantRequest> executionRequests;
        List<ExecutionGrant> availableExecutionGrants = new ArrayList<>(profile.externalGrants());
        availableExecutionGrants.addAll(context.getExecutionGrants());
        try {
            executionRequests = executionGrantValidator.requests(name, normalized.canonicalInput(), profile);
            requestMissingExecutionGrants(executionRequests, availableExecutionGrants, context);
            executionGrantValidator.validate(executionRequests, availableExecutionGrants);
        } catch (IllegalArgumentException denied) {
            return reject(call, "execution_grant_denied", "execution_grant_denied", EffectProfile.unknown());
        }
        ExecutionProfile effectiveProfile = profile.withExternalGrants(executionRequests.stream()
                .map(request -> new ExecutionGrant(request.canonicalPath(), request.access(), GrantLifetime.ONCE)).toList());
        call.setExecutionProfile(effectiveProfile);
        CallEffectAssessment effect = registry.assessEffect(name, call, effectiveProfile);
        if (!effect.trusted() || !profile.allows(effect.profile())) {
            return reject(call, "execution_profile_denied", "execution_profile_denied", effect.profile());
        }
        PermissionDecision decision = Objects.requireNonNull(policy, "policy must not be null")
                .evaluate(normalized.permissionSubject());
        if (decision.action() == PermissionAction.DENY) {
            return reject(call, "permission_denied", decision.reasonCode(), effect.profile());
        }
        if (decision.action() == PermissionAction.ASK && !hasReusableGrant(context, normalized, profile, decision)) {
            if (prompt == null) return reject(call, "approval_denied", "approval_unavailable", effect.profile());
            GrantLifetime lifetime = prompt.ask(display(call, normalized, profile), decision);
            if (lifetime == null) return reject(call, "approval_denied", "approval_denied", effect.profile());
            if (lifetime != GrantLifetime.ONCE) {
                PermissionGrant grant = PermissionGrant.issue(normalized.permissionSubject().exactKey(), profile, lifetime);
                try {
                    if (lifetime == GrantLifetime.WORKSPACE) {
                        Path workspace = context.getResolvedWorkspace();
                        if (workspace == null) return reject(call, "grant_persistence_failed", "grant_persistence_failed", effect.profile());
                        new WorkspacePermissionGrantStore().append(workspace, grant);
                    }
                    context.addPermissionGrant(grant);
                } catch (RuntimeException e) {
                    return reject(call, "grant_persistence_failed", "grant_persistence_failed", effect.profile());
                }
            }
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
        if (history == null || history.size() < 2) return false;
        return history.subList(history.size() - 2, history.size()).stream().allMatch(step ->
                name.equals(step.getTool()) && Objects.equals(input, step.getInput()));
    }

    private boolean hasReusableGrant(AgentContext context, NormalizedToolCall normalized,
                                     ExecutionProfile profile, PermissionDecision decision) {
        if (decision.perCallOnly() || decision.sourceIds().contains("builtin")) return false;
        return context.getPermissionGrants().stream().anyMatch(grant ->
                grant.matches(normalized.permissionSubject().exactKey(), profile));
    }

    private void requestMissingExecutionGrants(List<ExecutionGrantRequest> requests,
                                               List<ExecutionGrant> available,
                                               AgentContext context) {
        for (ExecutionGrantRequest request : requests) {
            boolean covered = available.stream().anyMatch(grant -> grant.access().includes(request.access())
                    && request.canonicalPath().startsWith(grant.canonicalPath()));
            if (covered) continue;
            if (prompt == null) throw new IllegalArgumentException("execution grant approval unavailable");
            GrantLifetime lifetime = prompt.askExecutionGrant(request);
            if (lifetime == null) throw new IllegalArgumentException("execution grant denied");
            ExecutionGrant grant = new ExecutionGrant(request.canonicalPath(), request.access(), lifetime);
            available.add(grant);
            if (lifetime != GrantLifetime.ONCE) context.addExecutionGrant(grant);
        }
    }
}
