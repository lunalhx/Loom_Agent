package cn.lunalhx.ai.infrastructure.mcp;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SanitizedStdioClientTransport extends StdioClientTransport {

    private static final Logger log = LoggerFactory.getLogger(SanitizedStdioClientTransport.class);

    private static final Set<String> DEFAULT_ALLOWLIST = Set.of("PATH", "HOME", "TMPDIR", "LANG");

    private final Set<String> envAllowlist;
    private final Map<String, String> explicitEnv;

    public SanitizedStdioClientTransport(List<String> envAllowlist,
                                         String command,
                                         List<String> args,
                                         Map<String, String> explicitEnv,
                                         McpJsonMapper jsonMapper) {
        super(buildServerParameters(envAllowlist, command, args, explicitEnv), jsonMapper);
        this.envAllowlist = envAllowlist == null || envAllowlist.isEmpty()
                ? DEFAULT_ALLOWLIST
                : Set.copyOf(envAllowlist);
        this.explicitEnv = Map.copyOf(explicitEnv != null ? explicitEnv : Map.of());
    }

    private static ServerParameters buildServerParameters(List<String> envAllowlist,
                                                           String command,
                                                           List<String> args,
                                                           Map<String, String> explicitEnv) {
        Set<String> allowlist = envAllowlist == null || envAllowlist.isEmpty()
                ? DEFAULT_ALLOWLIST
                : Set.copyOf(envAllowlist);

        java.util.Map<String, String> sanitizedEnv = new java.util.LinkedHashMap<>();
        Map<String, String> systemEnv = System.getenv();
        for (String key : allowlist) {
            String value = systemEnv.get(key);
            if (value != null) {
                sanitizedEnv.put(key, value);
            }
        }
        if (explicitEnv != null) {
            sanitizedEnv.putAll(explicitEnv);
        }

        ServerParameters.Builder builder = ServerParameters.builder(command);
        if (args != null && !args.isEmpty()) {
            builder.args(args);
        }
        builder.env(sanitizedEnv);

        log.info("MCP STDIO subprocess: command={}, envKeys={}", command, sanitizedEnv.keySet());
        return builder.build();
    }

    @Override
    protected ProcessBuilder getProcessBuilder() {
        ProcessBuilder pb = super.getProcessBuilder();
        // Double-ensure: clear any env that might have leaked in
        pb.environment().clear();
        Map<String, String> systemEnv = System.getenv();
        for (String key : envAllowlist) {
            String value = systemEnv.get(key);
            if (value != null) {
                pb.environment().put(key, value);
            }
        }
        for (var entry : explicitEnv.entrySet()) {
            pb.environment().put(entry.getKey(), entry.getValue());
        }
        return pb;
    }

    Set<String> getEffectiveEnvKeys() {
        Set<String> keys = new java.util.HashSet<>(envAllowlist);
        keys.addAll(explicitEnv.keySet());
        return Collections.unmodifiableSet(keys);
    }
}
