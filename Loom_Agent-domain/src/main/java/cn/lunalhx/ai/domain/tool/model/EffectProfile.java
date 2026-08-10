package cn.lunalhx.ai.domain.tool.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.EnumSet;
import java.util.Set;

/** Maximum possible effects for one call before execution. */
public record EffectProfile(Set<ToolEffect> effects,
                            OutboundDisclosure outboundDisclosure,
                            boolean complete) {

    public EffectProfile {
        effects = effects == null || effects.isEmpty()
                ? Set.of() : Set.copyOf(EnumSet.copyOf(effects));
        outboundDisclosure = outboundDisclosure == null
                ? OutboundDisclosure.UNKNOWN : outboundDisclosure;
    }

    public static EffectProfile unknown() {
        return new EffectProfile(Set.of(ToolEffect.REPOSITORY_READ,
                ToolEffect.DISPOSABLE_WRITE,
                ToolEffect.REPOSITORY_MUTATION,
                ToolEffect.EXTERNAL_READ,
                ToolEffect.EXTERNAL_MUTATION), OutboundDisclosure.UNKNOWN, false);
    }

    @JsonIgnore
    public boolean isReadOnly() {
        return complete
                && effects.size() == 1
                && effects.contains(ToolEffect.REPOSITORY_READ)
                && outboundDisclosure == OutboundDisclosure.NONE;
    }
}
