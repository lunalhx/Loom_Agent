package cn.lunalhx.ai.domain.tool.model;

import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Runtime-enforced capabilities for one tool invocation. */
public record ExecutionProfile(CollaborationMode mode, boolean delegateRun) {

    public ExecutionProfile {
        mode = Objects.requireNonNull(mode, "collaboration mode must not be null");
    }

    public static ExecutionProfile forRun(CollaborationMode mode, boolean delegateRun) {
        return new ExecutionProfile(mode, delegateRun);
    }

    public boolean allows(EffectProfile profile) {
        if (profile == null || !profile.complete()) {
            return false;
        }
        if (profile.outboundDisclosure() == OutboundDisclosure.UNKNOWN) {
            return mode == CollaborationMode.BUILD && !delegateRun;
        }
        if (profile.outboundDisclosure() == OutboundDisclosure.PRESENT && delegateRun) {
            return false;
        }
        Set<ToolEffect> effects = profile.effects();
        if (delegateRun && (effects.contains(ToolEffect.DISPOSABLE_WRITE)
                || effects.contains(ToolEffect.REPOSITORY_MUTATION)
                || effects.contains(ToolEffect.EXTERNAL_MUTATION))) {
            return false;
        }
        if (mode == CollaborationMode.PLAN) {
            EnumSet<ToolEffect> allowed = EnumSet.of(ToolEffect.REPOSITORY_READ,
                    ToolEffect.EXTERNAL_READ);
            return allowed.containsAll(effects);
        }
        return true;
    }

}
