package cn.lunalhx.ai.domain.agent.service.context;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Immutable, runtime-constructed redaction policy, mirroring loom-code's
 * {@code security.py} contract.
 *
 * <p>Carries:
 * <ul>
 *   <li>explicit {@code --secret-env-name} names and their effective values,</li>
 *   <li>auto-discovered {@code *_API_KEY}/{@code *_TOKEN}/{@code *_SECRET}/
 *       {@code *_PASSWORD} environment values,</li>
 *   <li>the current provider API key,</li>
 *   <li>the unified placeholder ({@code <redacted>}),</li>
 *   <li>pattern-based (second-layer) rule flags and scan limits.</li>
 * </ul>
 */
public final class SanitizationPolicy {

    public static final String PLACEHOLDER = "<redacted>";
    public static final String LEGACY_PLACEHOLDER = "[REDACTED]";
    public static final int RULES_VERSION = 1;

    private static final Set<String> SENSITIVE_SUFFIXES = Set.of("API_KEY", "TOKEN", "SECRET", "PASSWORD");

    private final Set<String> configuredNames;
    private final Set<String> secretValues;
    private final boolean heuristicEnabled;
    private final int maxScanChars;
    private final int minSecretLength;
    private final Set<String> envSnapshot;

    private SanitizationPolicy(Set<String> configuredNames, Set<String> secretValues,
                               boolean heuristicEnabled, int maxScanChars, int minSecretLength,
                               Set<String> envSnapshot) {
        this.configuredNames = Set.copyOf(configuredNames);
        this.secretValues = Set.copyOf(secretValues);
        this.heuristicEnabled = heuristicEnabled;
        this.maxScanChars = maxScanChars;
        this.minSecretLength = minSecretLength;
        this.envSnapshot = Set.copyOf(envSnapshot);
    }

    /** Explicit names only; no auto-discovery. */
    public static SanitizationPolicy of(Set<String> configuredNames, Set<String> secretValues) {
        return new SanitizationPolicy(normalizeNames(configuredNames), secretValues,
                false, 1_000_000, 8, System.getenv().keySet());
    }

    /** Explicit names + heuristic auto-discovery of sensitive env names. */
    public static SanitizationPolicy withEnvDiscovery(Set<String> configuredNames,
                                                      Set<String> providerKeys) {
        return withEnvDiscovery(configuredNames, providerKeys, null);
    }

    /** Explicit names + heuristic auto-discovery, merging extra literal secret
     *  values (e.g. test fixtures or direct CLI-provided values). */
    public static SanitizationPolicy withEnvDiscovery(Set<String> configuredNames,
                                                      Set<String> providerKeys,
                                                      Set<String> extraValues) {
        Set<String> values = new LinkedHashSet<>();
        if (providerKeys != null) {
            for (String key : providerKeys) {
                if (key != null && !key.isBlank()) {
                    values.add(key);
                }
            }
        }
        if (extraValues != null) {
            for (String key : extraValues) {
                if (key != null && !key.isBlank()) {
                    values.add(key);
                }
            }
        }
        values.addAll(discoverSecretValues(configuredNames));
        return new SanitizationPolicy(normalizeNames(configuredNames), values,
                true, 1_000_000, 8, System.getenv().keySet());
    }

    /** No secrets configured. */
    public static SanitizationPolicy empty() {
        return new SanitizationPolicy(Set.of(), Set.of(), false, 1_000_000, 8, System.getenv().keySet());
    }

    /**
     * Resolve configured secret env names to their actual values plus
     * heuristic env discovery, sorted for deterministic replacement.
     */
    public static Set<String> discoverSecretValues(Set<String> configuredNames) {
        Map<String, String> env = System.getenv();
        Set<String> values = new LinkedHashSet<>();
        Set<String> normalized = normalizeNames(configuredNames);
        for (Map.Entry<String, String> e : env.entrySet()) {
            String upper = e.getKey().toUpperCase();
            if (normalized.contains(upper) || looksSensitiveEnvName(upper)) {
                String value = e.getValue();
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    /** loom-code parity: sensitive env name detection. */
    public static boolean looksSensitiveEnvName(String upper) {
        if (upper == null) {
            return false;
        }
        return SENSITIVE_SUFFIXES.stream().anyMatch(suffix ->
                upper.endsWith("_" + suffix) || upper.endsWith(suffix));
    }

    private static Set<String> normalizeNames(Set<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        return names.stream().filter(n -> n != null && !n.isBlank())
                .map(String::toUpperCase).collect(Collectors.toUnmodifiableSet());
    }

    // ---- accessors ----

    /** Upper-cased configured secret env names. */
    public Set<String> configuredNames() {
        return configuredNames;
    }

    /** Effective secret values (configured + discovered + provider keys). */
    public Set<String> secretValues() {
        return secretValues;
    }

    public boolean heuristicEnabled() {
        return heuristicEnabled;
    }

    public int maxScanChars() {
        return maxScanChars;
    }

    public int minSecretLength() {
        return minSecretLength;
    }

    public Set<String> envSnapshot() {
        return envSnapshot;
    }

    public String placeholder() {
        return PLACEHOLDER;
    }

    /** Pattern-based second-layer rules (JWT/Bearer/token shapes), sorted
     *  longest-first with a rule id for audit events. */
    public java.util.List<PatternRule> patternRules() {
        java.util.List<PatternRule> rules = new java.util.ArrayList<>();
        if (heuristicEnabled) {
            rules.add(new PatternRule("jwt_bearer",
                    Pattern.compile("Bearer\\s+[A-Za-z0-9\\-._~+/]+=*"), PLACEHOLDER));
            rules.add(new PatternRule("private_key",
                    Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"), PLACEHOLDER));
        }
        return rules;
    }

    /** A second-layer heuristic rule; the matched content is never logged. */
    public record PatternRule(String id, Pattern pattern, String replacement) {
    }
}
