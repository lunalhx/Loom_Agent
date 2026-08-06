package cn.lunalhx.ai.domain.agent.service.context;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic secret redaction for persisted artifacts (observation,
 * session, checkpoint, trace, report). Configured env names and provider
 * keys are replaced with {@code [REDACTED]}. Best-effort: never throws.
 */
public final class SecretRedactor {

    public static final String REDACTED = "[REDACTED]";

    private static final Pattern SENSITIVE_NAME_SUFFIX =
            Pattern.compile("(API_KEY|TOKEN|SECRET|PASSWORD)$");

    /**
     * @param configured  extra env names marked secret via --secret-env-name
     * @param providerKeys actual provider api keys that must never leak
     */
    public static SecretRedactor of(Set<String> configured, Set<String> providerKeys) {
        return new SecretRedactor(configured, providerKeys);
    }

    public static SecretRedactor of(Set<String> configured) {
        return new SecretRedactor(configured, Set.of());
    }

    public static SecretRedactor none() {
        return new SecretRedactor(Set.of(), Set.of());
    }

    private final Set<String> configured;
    private final Set<String> providerKeys;

    private SecretRedactor(Set<String> configured, Set<String> providerKeys) {
        this.configured = configured;
        this.providerKeys = providerKeys;
    }

    public String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String out = value;
        for (String secret : configured) {
            if (secret != null && !secret.isBlank()) {
                out = out.replace(secret, REDACTED);
            }
        }
        for (String key : providerKeys) {
            if (key != null && !key.isBlank()) {
                out = out.replace(key, REDACTED);
            }
        }
        return out;
    }

    /** Redact values of a map recursively (keys untouched). */
    public Map<String, Object> redactMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return map;
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            out.put(e.getKey(), redactValue(e.getValue()));
        }
        return out;
    }

    private Object redactValue(Object value) {
        if (value instanceof String s) {
            return redact(s);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> nested = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                nested.put(String.valueOf(e.getKey()), redactValue(e.getValue()));
            }
            return nested;
        }
        if (value instanceof Iterable<?> it) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            for (Object item : it) {
                list.add(redactValue(item));
            }
            return list;
        }
        return value;
    }

    /** Heuristic: a config key whose name ends in a sensitive suffix. */
    public static boolean isSensitiveName(String key) {
        return key != null && SENSITIVE_NAME_SUFFIX.matcher(key.toUpperCase()).find();
    }
}
