package cn.lunalhx.ai.domain.tool.model;

import java.util.Objects;

/** A compiled rule from a validated permission source. */
public record PermissionRule(String id, String sourceId, String tool,
                             MatcherKind matcherKind, String match,
                             PermissionAction action) {
    public enum MatcherKind { TOOL, EXACT_CALL, SHELL_PREFIX, PATH_PREFIX, DOMAIN }

    public PermissionRule {
        id = requireText(id, "id");
        sourceId = requireText(sourceId, "sourceId");
        tool = tool == null ? "" : tool;
        matcherKind = Objects.requireNonNull(matcherKind, "matcherKind must not be null");
        match = requireText(match, "match");
        action = Objects.requireNonNull(action, "action must not be null");
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
