package cn.lunalhx.ai.domain.tool.model;

/** Trusted, call-specific effect evidence produced after argument validation. */
public record CallEffectAssessment(EffectProfile profile, boolean trusted) {

    public CallEffectAssessment {
        profile = profile == null ? EffectProfile.unknown() : profile;
    }

    public static CallEffectAssessment trusted(EffectProfile profile) {
        return new CallEffectAssessment(profile, true);
    }

    public static CallEffectAssessment untrusted() {
        return new CallEffectAssessment(EffectProfile.unknown(), false);
    }
}
