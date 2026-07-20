package cn.lunalhx.ai.infrastructure.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class SandboxEnvPolicy {

    public enum Mode { BLACKLIST, ALLOWLIST }

    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(".*(KEY|SECRET|TOKEN|PASS|CREDENTIAL|DSN).*", Pattern.CASE_INSENSITIVE);

    private static final Set<String> EXACT_BLOCKLIST = Set.of(
            "DATABASE_URL", "REDIS_URL", "MONGODB_URI", "POSTGRES_URL", "MYSQL_URL", "JDBC_URL",
            "GITHUB_PAT", "GH_TOKEN",
            "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
            "GOOGLE_APPLICATION_CREDENTIALS", "AZURE_CLIENT_SECRET",
            "SSH_AUTH_SOCK", "DOCKER_AUTH_CONFIG",
            "NPM_TOKEN", "PYPI_TOKEN"
    );

    private static final Pattern ENV_KEY_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final Mode mode;
    private final Set<String> allowlist;
    private final Set<String> extraBlocklist;

    public SandboxEnvPolicy(Mode mode, Set<String> allowlist, Set<String> extraBlocklist) {
        this.mode = mode;
        this.allowlist = allowlist != null ? allowlist : Set.of();
        this.extraBlocklist = extraBlocklist != null ? extraBlocklist : Set.of();
    }

    public Map<String, String> filter(Map<String, String> parentEnv, Map<String, String> extraEnv) {
        Map<String, String> result = new LinkedHashMap<>();

        if (mode == Mode.ALLOWLIST) {
            for (String key : allowlist) {
                if (parentEnv.containsKey(key)) {
                    result.put(key, parentEnv.get(key));
                }
            }
        } else {
            for (var entry : parentEnv.entrySet()) {
                String key = entry.getKey();
                if (isBlocked(key)) continue;
                result.put(key, entry.getValue());
            }
        }

        if (extraEnv != null) {
            for (var entry : extraEnv.entrySet()) {
                if (!ENV_KEY_PATTERN.matcher(entry.getKey()).matches()) {
                    throw new IllegalArgumentException("Invalid env key: " + entry.getKey());
                }
                if (isBlocked(entry.getKey())
                        || mode == Mode.ALLOWLIST && !allowlist.contains(entry.getKey())) {
                    throw new IllegalArgumentException("Env key is not allowed: " + entry.getKey());
                }
                result.put(entry.getKey(), entry.getValue());
            }
        }

        return result;
    }

    public List<String> extraEnvKeys(Map<String, String> extraEnv) {
        if (extraEnv == null || extraEnv.isEmpty()) return List.of();
        return extraEnv.keySet().stream().sorted().toList();
    }

    private boolean isBlocked(String key) {
        if (EXACT_BLOCKLIST.contains(key.toUpperCase())) return true;
        if (extraBlocklist.contains(key.toUpperCase())) return true;
        return SENSITIVE_KEY_PATTERN.matcher(key).matches();
    }
}
