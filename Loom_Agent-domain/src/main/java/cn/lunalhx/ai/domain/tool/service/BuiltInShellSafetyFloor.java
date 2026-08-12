package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.PermissionSubject;

import java.util.Locale;
import java.util.regex.Pattern;

/** Recognizable Shell hazards that remain effective under Full Access's ALLOW default. */
public final class BuiltInShellSafetyFloor {

    public record Decision(PermissionAction action, String ruleId, boolean perCallOnly) {
        static final Decision NONE = new Decision(null, "", false);
    }

    private static final Pattern SAFE_READ = Pattern.compile(
            "(?:pwd|ls|cat|head|tail|sed -n|wc|stat|file|rg|grep)(?:\\s|$)|"
                    + "git (?:status|diff|log|show|rev-parse)(?:\\s|$)");
    private static final Pattern ASK_ACTION = Pattern.compile(
            "(?:rm|mv|cp|mkdir|touch|chmod|chown|sudo|launchctl|brew|apt(?:-get)?|yum|dnf|"
                    + "git (?:add|commit|reset|restore|clean|checkout|switch|merge|rebase|push|fetch|pull|clone)|"
                    + "npm (?:publish|install)|mvn (?:deploy|install)|curl|wget|ssh|scp)(?:\\s|$)");
    private static final Pattern HOST_CREDENTIAL = Pattern.compile(
            "(?:^|\\s)(?:~?/)?(?:\\.ssh(?:/|$)|\\.kube/config(?:\\s|$)|\\.npmrc(?:\\s|$)|"
                    + "(?:\\S*/)?settings\\.xml(?:\\s|$))");
    private static final Pattern REPOSITORY_SECRET = Pattern.compile(
            "(?:^|[\\s/])(?:\\.env|id_rsa|id_ed25519|.*(?:credential|secret|private[_-]?key).*?)(?:[\\s/]|$)");

    private BuiltInShellSafetyFloor() {
    }

    public static Decision evaluate(PermissionSubject subject) {
        if (!"run_shell".equals(subject.toolName()) || subject.shellUnits().isEmpty()) {
            return Decision.NONE;
        }
        Decision strictest = Decision.NONE;
        for (String unit : subject.shellUnits()) {
            Decision decision = evaluateUnit(unit == null ? "" : unit.trim());
            if (rank(decision.action()) > rank(strictest.action())) strictest = decision;
        }
        return strictest;
    }

    private static Decision evaluateUnit(String unit) {
        String lower = unit.toLowerCase(Locale.ROOT);
        if (lower.contains(":(){ :|:& };:") || lower.matches("(?:rm\\s+.*(?:^|\\s)(?:/|~)(?:\\s|$).*|mkfs(?:\\.\\S+)?\\s+.*|"
                + "dd\\s+.*(?:of=)?/dev/\\S+.*|(?:shutdown|reboot|halt|poweroff)(?:\\s|$)).*")) {
            return new Decision(PermissionAction.DENY, "builtin-shell-catastrophic", false);
        }
        if (HOST_CREDENTIAL.matcher(lower).find()) {
            return new Decision(PermissionAction.ASK, "builtin-host-credential-read", true);
        }
        if (REPOSITORY_SECRET.matcher(lower).find() && !exampleSecret(lower)) {
            return new Decision(PermissionAction.ASK, "builtin-sensitive-read", false);
        }
        if (ASK_ACTION.matcher(lower).lookingAt()) {
            return new Decision(PermissionAction.ASK, "builtin-shell-impact", false);
        }
        if (SAFE_READ.matcher(lower).lookingAt()) {
            return new Decision(PermissionAction.ALLOW, "builtin-shell-read", false);
        }
        return Decision.NONE;
    }

    private static boolean exampleSecret(String unit) {
        return unit.contains(".env.example") || unit.contains(".env.sample")
                || unit.contains(".env.template") || unit.contains(".env.dist");
    }

    private static int rank(PermissionAction action) {
        if (action == null) return -1;
        return switch (action) { case ALLOW -> 0; case ASK -> 1; case DENY -> 2; };
    }
}
