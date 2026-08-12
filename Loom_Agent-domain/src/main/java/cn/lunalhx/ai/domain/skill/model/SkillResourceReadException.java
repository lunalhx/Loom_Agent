package cn.lunalhx.ai.domain.skill.model;

/** Fail-closed read errors for Skill Resource Observation. */
public final class SkillResourceReadException extends RuntimeException {

    public SkillResourceReadException(String message) {
        super(message);
    }

    public SkillResourceReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
