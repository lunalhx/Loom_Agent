package cn.lunalhx.ai.domain.tool.model;

import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime-enforced capability value.  Permission decisions never alter it.
 * Profile comparison is set containment, never enum ordinal comparison.
 */
public record ExecutionProfile(ExecutionProfileKind kind, Path workspace,
                               FilesystemAccess workspaceAccess, Path homeRoot,
                               Path temporaryRoot, boolean networkAllowed,
                               boolean hostPrivateVisible,
                               List<ExecutionGrant> externalGrants,
                               String sandboxBackend) {
    public ExecutionProfile {
        kind = Objects.requireNonNull(kind, "kind must not be null");
        workspaceAccess = Objects.requireNonNull(workspaceAccess, "workspaceAccess must not be null");
        externalGrants = externalGrants == null ? List.of() : List.copyOf(externalGrants);
        sandboxBackend = sandboxBackend == null ? "unresolved" : sandboxBackend;
    }

    public static ExecutionProfile forRun(CollaborationMode mode, boolean delegateRun) {
        if (delegateRun) {
            return new ExecutionProfile(ExecutionProfileKind.DELEGATE_SANDBOX, null,
                    FilesystemAccess.READ, null, null, false, false, List.of(), "unresolved");
        }
        if (mode == CollaborationMode.PLAN) {
            return new ExecutionProfile(ExecutionProfileKind.PLAN_SANDBOX, null,
                    FilesystemAccess.READ, null, null, false, false, List.of(), "unresolved");
        }
        return new ExecutionProfile(ExecutionProfileKind.BUILD_SANDBOX, null,
                FilesystemAccess.WRITE, null, null, false, false, List.of(), "unresolved");
    }

    public boolean allows(EffectProfile profile) {
        if (profile == null) return false;
        if (!profile.complete()) return kind == ExecutionProfileKind.BUILD_SANDBOX
                || kind == ExecutionProfileKind.DANGER_FULL_ACCESS;
        if (kind == ExecutionProfileKind.DANGER_FULL_ACCESS) return true;
        if (profile.outboundDisclosure() == OutboundDisclosure.UNKNOWN) {
            return kind == ExecutionProfileKind.BUILD_SANDBOX;
        }
        Set<ToolEffect> effects = profile.effects();
        if (kind == ExecutionProfileKind.PLAN_SANDBOX || kind == ExecutionProfileKind.DELEGATE_SANDBOX) {
            return profile.outboundDisclosure() == OutboundDisclosure.NONE
                    && EnumSet.of(ToolEffect.REPOSITORY_READ, ToolEffect.EXTERNAL_READ)
                    .containsAll(effects);
        }
        return profile.outboundDisclosure() != OutboundDisclosure.PRESENT
                || kind == ExecutionProfileKind.BUILD_SANDBOX;
    }

    /** True when this profile grants no more authority than {@code other}. */
    public boolean isSameOrStricterThan(ExecutionProfile other) {
        if (other == null) return false;
        if (kind == ExecutionProfileKind.DANGER_FULL_ACCESS) return other.kind == kind;
        if (other.kind == ExecutionProfileKind.DANGER_FULL_ACCESS) return true;
        if (networkAllowed && !other.networkAllowed) return false;
        if (hostPrivateVisible && !other.hostPrivateVisible) return false;
        if (!other.workspaceAccess.includes(workspaceAccess)) return false;
        return externalGrants.stream().allMatch(grant -> other.externalGrants.stream().anyMatch(otherGrant ->
                otherGrant.access().includes(grant.access())
                        && grant.canonicalPath().startsWith(otherGrant.canonicalPath())));
    }
}
