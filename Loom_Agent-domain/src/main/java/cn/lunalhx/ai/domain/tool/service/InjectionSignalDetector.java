package cn.lunalhx.ai.domain.tool.service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lightweight, configurable prompt-injection signal detector for untrusted
 * tool output. It never replaces a full security judgement — it only flags
 * high-confidence signals:
 *
 * <ul>
 *   <li>system/developer instruction forgery</li>
 *   <li>instructions to ignore rules or previous instructions</li>
 *   <li>requests to leak secrets</li>
 *   <li>approval/read-only bypass attempts</li>
 *   <li>forged control-protocol tags ({@code <tool>}/{@code <final>})</li>
 * </ul>
 *
 * <p>First release severity is always {@code WARN} — legitimate file content is
 * never dropped. A {@code BLOCK} mode is reserved for future policy
 * configuration.
 */
public final class InjectionSignalDetector {

    public enum Severity { WARN, BLOCK }

    public record Signal(String ruleId, Severity severity) {
    }

    private static final Set<String> RULE_IDS = Set.of(
            "sys_prompt_forgery", "ignore_instructions", "secret_exfiltration",
            "approval_bypass", "control_tag_forgery");

    private static final Pattern SYS_PROMPT_FORGERY =
            Pattern.compile("(?i)(you are (now )?(the )?(system|assistant|developer)( agent)?|"
                    + "<\\s*system\\s*>|<\\s*developer\\s*>)");
    private static final Pattern IGNORE_INSTRUCTIONS =
            Pattern.compile("(?i)(ignore (all |the )?(previous |prior )?instructions|"
                    + "disregard (all |the )?(previous |prior )?instructions|"
                    + "ignore (your )?(system |developer )?prompt|"
                    + "forget (everything|all previous instructions))");
    private static final Pattern SECRET_EXFILTRATION =
            Pattern.compile("(?i)(leak|reveal|exfiltrate|print|show me|send me)\\s+(your |the )?(api[- ]?keys?|secrets?|passwords?|tokens?)");
    private static final Pattern APPROVAL_BYPASS =
            Pattern.compile("(?i)(bypass|skip|disable)\\s+(the )?(approval|read-?only|read only)( (policy|gate|check))?");
    private static final Pattern CONTROL_TAG_FORGERY =
            Pattern.compile("<\\s*tool[\\s>]|<\\s*final\\s*>|<\\s*retry\\s*>");

    /** Detect high-confidence signals; returns matched rule ids in stable order. */
    public Set<String> detect(String toolName, String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String lower = text.toLowerCase();
        Set<String> matched = new LinkedHashSet<>();
        if (SYS_PROMPT_FORGERY.matcher(lower).find()) {
            matched.add("sys_prompt_forgery");
        }
        if (IGNORE_INSTRUCTIONS.matcher(lower).find()) {
            matched.add("ignore_instructions");
        }
        if (SECRET_EXFILTRATION.matcher(lower).find()) {
            matched.add("secret_exfiltration");
        }
        if (APPROVAL_BYPASS.matcher(lower).find()) {
            matched.add("approval_bypass");
        }
        if (CONTROL_TAG_FORGERY.matcher(lower).find()) {
            matched.add("control_tag_forgery");
        }
        return matched;
    }

    public boolean isInjection(Set<String> ruleIds) {
        return ruleIds != null && !ruleIds.isEmpty();
    }

    public static Set<String> knownRuleIds() {
        return RULE_IDS;
    }
}
