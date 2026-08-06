package cn.lunalhx.ai.domain.tool.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Approval-safe argument summary. Never shows full command text, write/patch
 * content or secret values — only tool name, path values, per-arg content
 * length and a content hash.
 */
public final class ApprovalDisplay {

    private ApprovalDisplay() {
    }

    /** Keys whose values are workspace paths and are safe to display. */
    private static final java.util.Set<String> PATH_KEYS = java.util.Set.of("path", "filePath");

    /** Summarize args into a redaction-safe map: key -> value summary. */
    public static Map<String, Object> summarize(JsonNode args) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (args == null || !args.isObject()) {
            return summary;
        }
        List<String> keys = new ArrayList<>();
        args.fieldNames().forEachRemaining(keys::add);
        keys.sort(Comparator.naturalOrder());
        for (String key : keys) {
            JsonNode value = args.get(key);
            if (value == null || value.isNull() || value.isMissingNode()) {
                summary.put(key, "(empty)");
                continue;
            }
            if (PATH_KEYS.contains(key) && value.isTextual()) {
                summary.put(key, value.asText());
                continue;
            }
            String text = value.isTextual() ? value.asText()
                    : value.isValueNode() ? value.asText() : value.toString();
            if (value.isContainerNode()) {
                summary.put(key, Map.of(
                        "type", value.isArray() ? "array" : "object",
                        "length", text.length(),
                        "sha256", DigestUtils.sha256Hex(text).substring(0, 16)));
            } else {
                summary.put(key, Map.of(
                        "length", text.length(),
                        "sha256", DigestUtils.sha256Hex(text).substring(0, 16)));
            }
        }
        return summary;
    }
}
