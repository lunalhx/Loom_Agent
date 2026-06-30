package cn.lunalhx.ai.infrastructure.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class McpToolNameGenerator {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_-]+");
    private static final int MAX_LENGTH = 64;

    private McpToolNameGenerator() {
    }

    public static String generate(String serverAlias, String remoteToolName) {
        String safeServer = sanitize(serverAlias);
        String safeTool = sanitize(remoteToolName);
        String base = "mcp__" + safeServer + "__" + safeTool;

        if (base.length() <= MAX_LENGTH) {
            return base;
        }

        String hash = sha256First8(serverAlias + ":" + remoteToolName);
        int maxPrefix = MAX_LENGTH - hash.length() - 1;
        return base.substring(0, Math.min(base.length(), maxPrefix)) + "_" + hash;
    }

    public static String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (ALLOWED.matcher(String.valueOf(c)).matches()) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String result = sb.toString().replaceAll("_+", "_").replaceAll("(^_|_$)", "");
        if (result.isEmpty()) {
            return "unknown";
        }
        return result.toLowerCase(Locale.ROOT);
    }

    private static String sha256First8(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static void checkConflicts(java.util.List<String> names) {
        Set<String> seen = new java.util.HashSet<>();
        for (String name : names) {
            if (!seen.add(name)) {
                throw new IllegalStateException("MCP tool name conflict: " + name);
            }
        }
    }
}
