package cn.lunalhx.ai.domain.tool.model;

import java.util.EnumSet;
import java.util.Set;

/** Conservative, tool-level effect envelope used as classification input. */
public record ToolCapabilityEnvelope(Set<ToolEffect> effects,
                                     OutboundDisclosure outboundDisclosure,
                                     boolean complete,
                                     boolean trusted) {

    public ToolCapabilityEnvelope {
        effects = effects == null || effects.isEmpty()
                ? Set.of() : Set.copyOf(EnumSet.copyOf(effects));
        outboundDisclosure = outboundDisclosure == null
                ? OutboundDisclosure.UNKNOWN : outboundDisclosure;
    }

    public static ToolCapabilityEnvelope repositoryRead() {
        return trusted(Set.of(ToolEffect.REPOSITORY_READ), OutboundDisclosure.NONE);
    }

    public static ToolCapabilityEnvelope repositoryMutation() {
        return trusted(Set.of(ToolEffect.REPOSITORY_MUTATION), OutboundDisclosure.NONE);
    }

    public static ToolCapabilityEnvelope shell() {
        return trusted(Set.of(ToolEffect.REPOSITORY_READ,
                ToolEffect.DISPOSABLE_WRITE,
                ToolEffect.REPOSITORY_MUTATION,
                ToolEffect.EXTERNAL_READ,
                ToolEffect.EXTERNAL_MUTATION), OutboundDisclosure.UNKNOWN);
    }

    public static ToolCapabilityEnvelope trusted(Set<ToolEffect> effects,
                                                 OutboundDisclosure disclosure) {
        return new ToolCapabilityEnvelope(effects, disclosure, true, true);
    }

    public static ToolCapabilityEnvelope untrustedUnknown() {
        return new ToolCapabilityEnvelope(Set.of(), OutboundDisclosure.UNKNOWN, false, false);
    }

    public EffectProfile toEffectProfile() {
        return new EffectProfile(effects, outboundDisclosure, complete);
    }
}
