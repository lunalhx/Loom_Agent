package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.model.NormalizedToolCall;
import cn.lunalhx.ai.domain.tool.model.PermissionSubject;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;

/** Canonicalizes tool inputs. Shell parsing is deliberately conservative. */
public final class ToolCallNormalizer {
    private final ObjectMapper mapper;

    public ToolCallNormalizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public NormalizedToolCall normalize(ToolCall call) {
        JsonNode canonical = canonicalize(call.getInput() == null ? mapper.createObjectNode() : call.getInput());
        String tool = call.getName() == null ? "" : call.getName();
        String key = sha256(tool + "\n" + canonical);
        if (!"run_shell".equals(tool)) {
            return new NormalizedToolCall(tool, canonical,
                    new PermissionSubject(tool, key, List.of(), false, paths(canonical), List.of()));
        }
        String command = canonical.path("command").asText("");
        ShellParse parse = parseShell(command);
        return new NormalizedToolCall(tool, canonical,
                new PermissionSubject(tool, key, parse.units(), parse.opaque(), List.of(), List.of()));
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            for (String key : keys) result.set(key, canonicalize(node.get(key)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            for (JsonNode value : node) result.add(canonicalize(value));
            return result;
        }
        return node;
    }

    private List<String> paths(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (!node.isObject()) return values;
        for (String field : List.of("path", "source", "target", "directory")) {
            if (node.path(field).isTextual()) values.add(node.path(field).asText());
        }
        if (node.path("external_access").isArray()) {
            for (JsonNode entry : node.path("external_access")) {
                if (entry.path("path").isTextual()) values.add(entry.path("path").asText());
            }
        }
        return values;
    }

    private ShellParse parseShell(String command) {
        if (command == null || command.isBlank() || hasOpaqueSyntax(command)) return ShellParse.OPAQUE;
        List<String> units = new ArrayList<>();
        StringBuilder unit = new StringBuilder();
        boolean single = false;
        boolean doub = false;
        boolean escaped = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaped) { unit.append(c); escaped = false; continue; }
            if (c == '\\' && !single) { escaped = true; continue; }
            if (c == '\'' && !doub) { single = !single; continue; }
            if (c == '"' && !single) { doub = !doub; continue; }
            if (!single && !doub && (c == ';' || c == '\n' || (c == '&' && i + 1 < command.length()
                    && command.charAt(i + 1) == '&') || (c == '|' && i + 1 < command.length()
                    && command.charAt(i + 1) == '|') || c == '|')) {
                if (unit.toString().trim().isEmpty()) return ShellParse.OPAQUE;
                units.add(normalizeUnit(unit.toString()));
                unit.setLength(0);
                if (c == '&' || c == '|') i++;
                continue;
            }
            unit.append(c);
        }
        if (single || doub || escaped || unit.toString().trim().isEmpty()) return ShellParse.OPAQUE;
        units.add(normalizeUnit(unit.toString()));
        return new ShellParse(List.copyOf(units), false);
    }

    private boolean hasOpaqueSyntax(String command) {
        return command.contains("$") || command.contains("`") || command.contains("<") || command.contains(">")
                || command.contains("*") || command.contains("?") || command.contains("[") || command.contains("{")
                || command.contains("}") || command.contains("(") || command.contains(")") || command.contains("&") && !command.contains("&&");
    }

    private String normalizeUnit(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record ShellParse(List<String> units, boolean opaque) {
        private static final ShellParse OPAQUE = new ShellParse(List.of(), true);
    }
}
