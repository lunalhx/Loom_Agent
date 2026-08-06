package cn.lunalhx.ai.infrastructure.gateway;

import java.util.List;
import java.util.Map;

/**
 * Minimal JSON writer/parser shared by the provider transport. Kept dependency
 * free so the gateway stays a plain java.net.http client.
 */
final class JsonSupport {

    private JsonSupport() {
    }

    static String toJson(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            sb.append(jsonValue(e.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static String jsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            return sb.append('"').toString();
        }
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(jsonValue(String.valueOf(e.getKey()))).append(':')
                        .append(jsonValue(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(jsonValue(item));
            }
            return sb.append(']').toString();
        }
        return jsonValue(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseJson(String body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
        } catch (Exception e) {
            throw new HttpModelGateway.ModelGatewayTransportException(
                    "backend returned non-JSON content that could not be parsed: " + body);
        }
    }

    static String asText(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isEmpty() ? null : s;
    }

    @SuppressWarnings("unchecked")
    static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }
}
