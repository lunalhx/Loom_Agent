package cn.lunalhx.ai.domain.skill.model;

/** Fail-closed explicit Skill Invocation error before the first model call. */
public final class SkillActivationException extends RuntimeException {
    public SkillActivationException(String message) {
        super(message);
    }

    public SkillActivationException(String message, Throwable cause) {
        super(message, cause);
    }
}
