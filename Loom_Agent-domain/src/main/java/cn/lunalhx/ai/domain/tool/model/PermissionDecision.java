package cn.lunalhx.ai.domain.tool.model;

import java.util.List;

/** Immutable, auditable result of evaluating the frozen permission policy. */
public record PermissionDecision(PermissionAction action, String reasonCode,
                                 List<String> matchedRuleIds, List<String> sourceIds,
                                 boolean perCallOnly) {
    public PermissionDecision {
        action = action == null ? PermissionAction.DENY : action;
        reasonCode = reasonCode == null ? "permission_denied" : reasonCode;
        matchedRuleIds = matchedRuleIds == null ? List.of() : List.copyOf(matchedRuleIds);
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
    }
}
