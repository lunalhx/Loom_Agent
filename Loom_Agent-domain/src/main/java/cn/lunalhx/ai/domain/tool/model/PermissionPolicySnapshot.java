package cn.lunalhx.ai.domain.tool.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Frozen compiled policy for one root run. */
public final class PermissionPolicySnapshot {
    private final PermissionAction defaultAction;
    private final List<PermissionRule> compiledRules;
    private final List<String> sourceDigests;
    private final String snapshotDigest;

    public PermissionPolicySnapshot(PermissionAction defaultAction, List<PermissionRule> compiledRules,
                                    List<String> sourceDigests) {
        this.defaultAction = Objects.requireNonNull(defaultAction, "defaultAction must not be null");
        this.compiledRules = compiledRules == null ? List.of() : List.copyOf(compiledRules);
        this.sourceDigests = sourceDigests == null ? List.of() : List.copyOf(sourceDigests);
        this.snapshotDigest = digest(defaultAction + "|" + this.compiledRules + "|" + this.sourceDigests);
    }

    public PermissionAction defaultAction() { return defaultAction; }
    public List<PermissionRule> compiledRules() { return compiledRules; }
    public List<String> sourceDigests() { return sourceDigests; }
    public String snapshotDigest() { return snapshotDigest; }

    public PermissionDecision evaluate(PermissionSubject subject) {
        List<PermissionRule> matches = new ArrayList<>();
        for (PermissionRule rule : compiledRules) {
            if (matches(rule, subject)) {
                matches.add(rule);
            }
        }
        PermissionAction action = defaultAction;
        if (matches.stream().anyMatch(r -> r.action() == PermissionAction.DENY)) {
            action = PermissionAction.DENY;
        } else if (matches.stream().anyMatch(r -> r.action() == PermissionAction.ASK)) {
            action = PermissionAction.ASK;
        } else if (matches.stream().anyMatch(r -> r.action() == PermissionAction.ALLOW)) {
            action = PermissionAction.ALLOW;
        }
        return new PermissionDecision(action,
                matches.isEmpty() ? "default_" + defaultAction.name().toLowerCase() : "matched_rule",
                matches.stream().map(PermissionRule::id).toList(),
                matches.stream().map(PermissionRule::sourceId).distinct().toList(), false);
    }

    private boolean matches(PermissionRule rule, PermissionSubject subject) {
        if (!rule.tool().isEmpty() && !rule.tool().equals(subject.toolName())) return false;
        return switch (rule.matcherKind()) {
            case TOOL -> rule.match().equals(subject.toolName());
            case EXACT_CALL -> rule.match().equals(subject.exactKey());
            // Shell rules apply to individual executable units.  The evaluator then
            // combines every matched action with DENY > ASK > ALLOW, so a safe
            // leading unit can never conceal a later dangerous unit.
            case SHELL_PREFIX -> !subject.opaque() && subject.shellUnits().stream()
                    .anyMatch(u -> u.equals(rule.match()) || u.startsWith(rule.match() + " "));
            case PATH_PREFIX -> subject.paths().stream().anyMatch(p -> p.equals(rule.match()) || p.startsWith(rule.match() + "/"));
            case DOMAIN -> subject.domains().stream().anyMatch(d -> d.equals(rule.match()) || d.endsWith("." + rule.match()));
        };
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
