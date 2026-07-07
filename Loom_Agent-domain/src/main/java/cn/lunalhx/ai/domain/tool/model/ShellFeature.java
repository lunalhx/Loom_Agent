package cn.lunalhx.ai.domain.tool.model;

/**
 * Shell syntax features detected during command analysis.
 */
public enum ShellFeature {
    PIPE,
    REDIRECT,
    LOGICAL_OP,
    COMMAND_SUBSTITUTION,
    WILDCARD,
    BACKGROUND,
    MULTILINE,
    VARIABLE_EXPANSION
}
