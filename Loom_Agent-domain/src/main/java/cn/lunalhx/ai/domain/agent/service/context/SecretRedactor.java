package cn.lunalhx.ai.domain.agent.service.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic secret redaction for persisted artifacts (observation,
 * session, checkpoint, trace, report).
 *
 * <p>Behavior contract (loom-code parity):
 * <ol>
 *   <li>Exact values are replaced longest-first so a short secret never
 *       breaks the full match of a longer one.</li>
 *   <li>Field-name recursion: values under sensitive keys
 *       ({@code *_API_KEY}/{@code *_TOKEN}/{@code *_SECRET}/{@code *_PASSWORD})
 *       are replaced wholesale; nested {@code Map}/{@code List} handled.</li>
 *   <li>Empty and unsafe short values are filtered to avoid mangling plain
 *       text.</li>
 *   <li>Placeholder is {@code <redacted>} (README/Python aligned).</li>
 *   <li>Second-layer heuristic rules (JWT/Bearer/private-key shapes) carry
 *       rule ids; matched content is never logged.</li>
 * </ol>
 */
public final class SecretRedactor {

    public static final String REDACTED = SanitizationPolicy.PLACEHOLDER;

    private static final Pattern SENSITIVE_NAME_SUFFIX =
            Pattern.compile("(API_KEY|TOKEN|SECRET|PASSWORD)$");

    private final SanitizationPolicy policy;
    private final List<String> orderedSecrets;

    /** Legacy entry point: configured secret values + provider keys. */
    public static SecretRedactor of(Set<String> configured, Set<String> providerKeys) {
        return of(configured, Set.of(), providerKeys);
    }

    /** Configured env names + direct literal values + provider keys. */
    public static SecretRedactor of(Set<String> configured, Set<String> literalValues,
                                    Set<String> providerKeys) {
        Set<String> merged = new LinkedHashSet<>();
        if (literalValues != null) {
            merged.addAll(literalValues);
        }
        return new SecretRedactor(
                SanitizationPolicy.withEnvDiscovery(configured, providerKeys, merged));
    }

    public static SecretRedactor of(Set<String> configured) {
        return of(configured, Set.of());
    }

    public static SecretRedactor none() {
        return new SecretRedactor(SanitizationPolicy.empty());
    }

    /** Single source of truth: CLI and Spring both build through a policy. */
    public static SecretRedactor fromPolicy(SanitizationPolicy policy) {
        return new SecretRedactor(policy);
    }

    private SecretRedactor(SanitizationPolicy policy) {
        this.policy = policy == null ? SanitizationPolicy.empty() : policy;
        Set<String> merged = new LinkedHashSet<>(this.policy.secretValues());
        this.orderedSecrets = merged.stream()
                .filter(SecretRedactor::isUsableSecret)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    /** True when the value is long enough and not trivially colliding with
     *  ordinary text. */
    static boolean isUsableSecret(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.length() < 8) {
            return false;
        }
        return !value.equals(SanitizationPolicy.PLACEHOLDER)
                && !value.equals("[REDACTED]");
    }

    public String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String out = value;
        // Legacy marker compatibility: old artifacts written with [REDACTED]
        // are normalized to the current placeholder on rewrite so consumers
        // never see a mix of markers.
        if (out.contains("[REDACTED]")) {
            out = out.replace("[REDACTED]", REDACTED);
        }
        for (String secret : orderedSecrets) {
            out = out.replace(secret, REDACTED);
        }
        for (SanitizationPolicy.PatternRule rule : policy.patternRules()) {
            Matcher m = rule.pattern().matcher(out);
            out = m.replaceAll(Matcher.quoteReplacement(rule.replacement()));
        }
        return out;
    }

    /** Redact values of a map recursively (keys untouched). */
    public Map<String, Object> redactMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return map;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            out.put(e.getKey(), redactValue(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** Recursively redact any structured artifact (trace/report payloads). */
    public Object redactArtifact(Object value) {
        return redactValue(null, value);
    }

    private Object redactValue(String key, Object value) {
        if (value instanceof String s) {
            return isSensitiveName(key) ? REDACTED : redact(s);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                nested.put(String.valueOf(e.getKey()), redactValue(String.valueOf(e.getKey()), e.getValue()));
            }
            return nested;
        }
        if (value instanceof Iterable<?> it) {
            List<Object> list = new ArrayList<>();
            for (Object item : it) {
                list.add(redactValue(key, item));
            }
            return list;
        }
        return value;
    }

    /** Heuristic: a config key whose name ends in a sensitive suffix. */
    public static boolean isSensitiveName(String key) {
        return key != null && SENSITIVE_NAME_SUFFIX.matcher(key.toUpperCase()).find();
    }

    public SanitizationPolicy policy() {
        return policy;
    }

    public List<String> orderedSecrets() {
        return List.copyOf(orderedSecrets);
    }
}
