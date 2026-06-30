package cn.lunalhx.ai.infrastructure.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class McpInputSchemaSimplifier {

    private static final Logger log = LoggerFactory.getLogger(McpInputSchemaSimplifier.class);

    private final McpJsonMapper jsonMapper;
    private final int maxPropertyDescriptionChars;
    private final int maxProperties;
    private final int maxEnumValues;
    private final int maxSchemaChars;

    public McpInputSchemaSimplifier(McpJsonMapper jsonMapper,
                                    int maxPropertyDescriptionChars, int maxProperties,
                                    int maxEnumValues, int maxSchemaChars) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.maxPropertyDescriptionChars = maxPropertyDescriptionChars;
        this.maxProperties = maxProperties;
        this.maxEnumValues = maxEnumValues;
        this.maxSchemaChars = maxSchemaChars;
    }

    /**
     * Always returns a valid JSON object string.
     */
    public String simplify(McpSchema.JsonSchema schema) {
        if (schema == null) {
            return "{\"type\":\"object\"}";
        }

        String type = schema.type();
        if (type == null) {
            type = "object";
        }

        if (!"object".equals(type)) {
            return serializeWithCap(Map.of("type", type), List.of());
        }

        List<String> required = schema.required() == null ? List.of() : schema.required();
        List<String> requiredShown = required.subList(0, Math.min(required.size(), maxProperties));

        Map<String, Object> rawProps = schema.properties();
        if (rawProps == null) {
            rawProps = Map.of();
        }

        List<String> orderedNames = new ArrayList<>();
        // required first (preserving original order)
        for (String name : requiredShown) {
            orderedNames.add(name);
        }
        // then remaining optional properties
        for (String name : rawProps.keySet()) {
            if (!orderedNames.contains(name) && orderedNames.size() < maxProperties) {
                orderedNames.add(name);
            }
        }

        Map<String, Object> simplifiedProps = new LinkedHashMap<>();
        for (String name : orderedNames) {
            simplifiedProps.put(name, simplifyProperty(rawProps.get(name)));
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", simplifiedProps);
        if (!requiredShown.isEmpty()) {
            root.put("required", requiredShown);
        }

        return serializeWithCap(root, requiredShown);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> simplifyProperty(Object raw) {
        if (!(raw instanceof Map)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", "string");
            return out;
        }

        Map<String, Object> m = (Map<String, Object>) raw;
        Map<String, Object> out = new LinkedHashMap<>();

        Object typeObj = m.get("type");
        String type = resolveType(typeObj);
        if (type != null) {
            out.put("type", type);
        }

        if (m.containsKey("$ref")) {
            if (!out.containsKey("type")) {
                out.put("type", "object");
            }
            out.put("description", "(ref)");
            return out;
        }

        String desc = asString(m.get("description"));
        Object enumObj = m.get("enum");
        if (enumObj instanceof List<?> e) {
            if (e.size() <= maxEnumValues) {
                out.put("enum", e);
            } else {
                String prefix = (desc != null ? desc : "");
                desc = prefix + " (enum: " + e.size() + " values)";
            }
        }

        if (desc != null) {
            out.put("description", truncate(clean(desc), maxPropertyDescriptionChars));
        }

        if ("array".equals(type) && m.get("items") instanceof Map<?, ?> items) {
            Object itemType = items.get("type");
            if (itemType instanceof String s) {
                Map<String, Object> itemsMap = new LinkedHashMap<>();
                itemsMap.put("type", s);
                out.put("items", itemsMap);
            }
        }

        return out;
    }

    private static String resolveType(Object typeObj) {
        if (typeObj instanceof String s) {
            return s;
        }
        if (typeObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && s != null) {
                    return s;
                }
            }
        }
        return null;
    }

    private static String clean(String s) {
        return s.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
    }

    private static String truncate(String s, int max) {
        return s.substring(0, Math.min(s.length(), max));
    }

    @SuppressWarnings("unchecked")
    private String serializeWithCap(Map<String, Object> root, List<String> requiredShown) {
        try {
            String json = jsonMapper.writeValueAsString(root);
            if (json.length() <= maxSchemaChars) {
                return json;
            }
        } catch (Exception e) {
            log.debug("Schema serialization failed, falling back to progressive reduction", e);
        }

        // Progressive reduction: drop optional properties from the end
        Map<String, Object> properties = (Map<String, Object>) root.get("properties");
        if (properties != null && !properties.isEmpty()) {
            List<String> propNames = new ArrayList<>(properties.keySet());
            for (int i = propNames.size() - 1; i >= 0; i--) {
                String name = propNames.get(i);
                if (requiredShown.contains(name)) {
                    continue;
                }
                properties.remove(name);
                try {
                    String json = jsonMapper.writeValueAsString(root);
                    if (json.length() <= maxSchemaChars) {
                        return json;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // Even required-only is too large — minimal valid schema
        try {
            Map<String, Object> minimal = new LinkedHashMap<>();
            minimal.put("type", "object");
            if (!requiredShown.isEmpty()) {
                minimal.put("required", requiredShown);
            }
            return jsonMapper.writeValueAsString(minimal);
        } catch (Exception e) {
            return "{\"type\":\"object\"}";
        }
    }

    private static String asString(Object obj) {
        if (obj instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }
}
