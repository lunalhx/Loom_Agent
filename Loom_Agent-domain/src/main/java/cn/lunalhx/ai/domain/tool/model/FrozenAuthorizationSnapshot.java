package cn.lunalhx.ai.domain.tool.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Serializable frozen authorization for Run checkpoint resume.
 * Excludes disposable security-scope roots and Full Access host paths;
 * those are recreated at restore time.
 */
public record FrozenAuthorizationSnapshot(
        PermissionAction defaultAction,
        List<PermissionRule> compiledRules,
        List<String> sourceDigests,
        String snapshotDigest,
        ExecutionProfileKind profileKind,
        FilesystemAccess workspaceAccess,
        boolean networkAllowed,
        boolean hostPrivateVisible,
        String sandboxBackend,
        List<PermissionGrant> permissionGrants,
        List<ExecutionGrant> executionGrants,
        List<ExecutionGrant> externalGrants,
        String approvalPolicy) {

    public FrozenAuthorizationSnapshot {
        Objects.requireNonNull(defaultAction, "defaultAction");
        compiledRules = compiledRules == null ? List.of() : List.copyOf(compiledRules);
        sourceDigests = sourceDigests == null ? List.of() : List.copyOf(sourceDigests);
        Objects.requireNonNull(snapshotDigest, "snapshotDigest");
        Objects.requireNonNull(profileKind, "profileKind");
        Objects.requireNonNull(workspaceAccess, "workspaceAccess");
        sandboxBackend = sandboxBackend == null ? "unresolved" : sandboxBackend;
        permissionGrants = permissionGrants == null ? List.of() : List.copyOf(permissionGrants);
        executionGrants = executionGrants == null ? List.of() : List.copyOf(executionGrants);
        externalGrants = externalGrants == null ? List.of() : List.copyOf(externalGrants);
        approvalPolicy = approvalPolicy == null ? "ask" : approvalPolicy;
    }

    public static FrozenAuthorizationSnapshot capture(
            PermissionPolicySnapshot policy,
            ExecutionProfile profile,
            List<PermissionGrant> permissionGrants,
            List<ExecutionGrant> executionGrants,
            String approvalPolicy) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(profile, "profile");
        // Persist grants without disposable home/tmp/workspace roots; keep the
        // bound capability shape (including external grants) so matches() still
        // works after restore against the rehydrated Execution Profile.
        List<PermissionGrant> durablePermissionGrants = new ArrayList<>();
        for (PermissionGrant grant : permissionGrants == null ? List.<PermissionGrant>of() : permissionGrants) {
            ExecutionProfile bound = grant.executionProfile();
            ExecutionProfile durableBound = new ExecutionProfile(
                    bound.kind(),
                    null,
                    bound.workspaceAccess(),
                    null,
                    null,
                    bound.networkAllowed(),
                    bound.hostPrivateVisible(),
                    bound.externalGrants(),
                    bound.sandboxBackend());
            durablePermissionGrants.add(new PermissionGrant(
                    grant.saltedCallDigest(), grant.salt(), durableBound, grant.lifetime()));
        }
        return new FrozenAuthorizationSnapshot(
                policy.defaultAction(),
                policy.compiledRules(),
                policy.sourceDigests(),
                policy.snapshotDigest(),
                profile.kind(),
                profile.workspaceAccess(),
                profile.networkAllowed(),
                profile.hostPrivateVisible(),
                profile.sandboxBackend(),
                durablePermissionGrants,
                executionGrants,
                profile.externalGrants(),
                approvalPolicy);
    }

    public PermissionPolicySnapshot toPolicy() {
        PermissionPolicySnapshot policy = new PermissionPolicySnapshot(
                defaultAction, compiledRules, sourceDigests);
        if (!snapshotDigest.equals(policy.snapshotDigest())) {
            throw new IllegalArgumentException(
                    "frozen authorization snapshot digest mismatch; refusing restore");
        }
        return policy;
    }

    public ExecutionProfile toExecutionProfile(java.nio.file.Path workspace,
                                               java.nio.file.Path homeRoot,
                                               java.nio.file.Path temporaryRoot) {
        if (profileKind == ExecutionProfileKind.DANGER_FULL_ACCESS) {
            throw new IllegalArgumentException(
                    "Full Access runs are not recoverable after process restart");
        }
        return new ExecutionProfile(
                profileKind,
                workspace,
                workspaceAccess,
                homeRoot,
                temporaryRoot,
                networkAllowed,
                hostPrivateVisible,
                externalGrants,
                sandboxBackend);
    }
}
