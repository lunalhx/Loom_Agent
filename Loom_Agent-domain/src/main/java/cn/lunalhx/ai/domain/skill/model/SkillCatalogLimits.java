package cn.lunalhx.ai.domain.skill.model;

/** Deterministic catalog admission limits for metadata-only discovery. */
public final class SkillCatalogLimits {
    public static final int MAX_PACKAGES = 256;
    public static final int MAX_SKILL_MD_BYTES = 256 * 1024;
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_DESCRIPTION_LENGTH = 1024;
    /** Whole-body admission ceiling for one Active Skill instruction body. */
    public static final int MAX_ACTIVE_BODY_CHARS = 32 * 1024;
    /** Whole-body admission ceiling for all Active Skill instruction bodies in one Run. */
    public static final int MAX_ACTIVE_TOTAL_CHARS = 64 * 1024;

    private SkillCatalogLimits() {
    }
}
