package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, testable JSON decision parser for model outputs.
 *
 * <h3>Recovery pipeline</h3>
 * <ol>
 *   <li>Strip markdown fences (```json ... ```, ``` ... ```).</li>
 *   <li>Try strict JSON parse.</li>
 *   <li>If strict parse fails, extract one complete outer JSON object or remove a trailing comma.</li>
 *   <li>Validate required fields (type must be "action" or "final").</li>
 *   <li>Return field-level error info when validation fails.</li>
 * </ol>
 */
final class DecisionParser {

    private final ObjectMapper objectMapper;

    public DecisionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parse a raw model output string into an {@link AgentDecision}.
     *
     * @param rawOutput the raw text emitted by the model
     * @return the parsed decision
     * @throws DecisionParseException if the output cannot be recovered
     */
    public AgentDecision parse(String rawOutput) throws DecisionParseException {
        return parse(rawOutput, Set.of());
    }

    public AgentDecision parse(
            String rawOutput, Set<String> visibleTools) throws DecisionParseException {
        String text = StringUtils.trimToEmpty(rawOutput);
        if (text.isEmpty()) {
            throw new DecisionParseException(
                    DecisionParseErrorCode.EMPTY_OUTPUT,
                    "模型输出为空");
        }

        // 1. Strip markdown fences
        String stripped = stripMarkdownFence(text);

        // 2. Try strict JSON parse
        JsonNode root;
        try {
            root = objectMapper.readTree(stripped);
        } catch (Exception strictError) {
            // 3. Attempt deterministic normalisation
            String recovered = tryRecoverJson(stripped);
            if (recovered == null) {
                throw new DecisionParseException(
                        DecisionParseErrorCode.INVALID_JSON,
                        "模型输出不是合法 JSON",
                        truncateForError(text));
            }
            try {
                root = objectMapper.readTree(recovered);
            } catch (Exception recoveryError) {
                throw new DecisionParseException(
                        DecisionParseErrorCode.INVALID_JSON,
                        "JSON 修复后仍无法解析: " + recoveryError.getMessage(),
                        truncateForError(text));
            }
        }

        if (root == null || root.isMissingNode()) {
            throw new DecisionParseException(
                    DecisionParseErrorCode.INVALID_JSON,
                    "解析结果为空");
        }
        if (!root.isObject()) {
            throw new DecisionParseException(
                    DecisionParseErrorCode.NON_OBJECT_INPUT,
                    "模型输出必须是 JSON 对象");
        }

        root = normaliseWrapper(root);
        root = normaliseToolNameAsType(root, visibleTools);

        return buildDecision(root);
    }

    // ------------------------------------------------------------------
    // Normalisation
    // ------------------------------------------------------------------

    /**
     * Attempt deterministic JSON recovery for common model output errors.
     * Returns the recovered JSON string, or null if unrecoverable.
     */
    String tryRecoverJson(String text) {
        // Strategy 1: Find the outermost JSON braces and extract
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            String candidate = text.substring(firstBrace, lastBrace + 1).trim();
            if (isValidJson(candidate)) {
                return candidate;
            }
        }

        String fixed = fixCommonJsonErrors(text);
        if (fixed != null && !fixed.equals(text) && isValidJson(fixed)) {
            return fixed;
        }

        return null;
    }

    /**
     * Fix a trailing comma before a closing object or array delimiter.
     */
    String fixCommonJsonErrors(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        String fixed = text;

        // Remove trailing commas before } or ]
        fixed = fixed.replaceAll(",\\s*([}\\]])", "$1");

        // Try to parse as-is first
        if (isValidJson(fixed)) {
            return fixed;
        }

        return null;
    }

    /**
     * Normalise wrapper objects like {"action":{...}} or {"final":{...}}
     * to the standard form by unwrapping if the inner object has a valid type field,
     * or by merging the wrapper fields with the inner object.
     */
    JsonNode normaliseWrapper(JsonNode root) {
        // Check for {"action": {...}} wrapper
        if (root.has("action") && root.path("action").isObject()) {
            JsonNode actionBody = root.path("action");
            // If actionBody already has "type", use it directly
            if (actionBody.has("type")) {
                return actionBody;
            }
            // Merge: set type=action, copy fields from action body
            ObjectNode merged = objectMapper.createObjectNode();
            merged.put("type", "action");
            actionBody.fields().forEachRemaining(f -> merged.set(f.getKey(), f.getValue()));
            return merged;
        }

        // Check for {"final": {...}} wrapper
        if (root.has("final") && root.path("final").isObject()) {
            JsonNode finalBody = root.path("final");
            if (finalBody.has("type")) {
                return finalBody;
            }
            ObjectNode merged = objectMapper.createObjectNode();
            merged.put("type", "final");
            finalBody.fields().forEachRemaining(f -> merged.set(f.getKey(), f.getValue()));
            return merged;
        }

        return root;
    }

    JsonNode normaliseToolNameAsType(JsonNode root, Set<String> visibleTools) {
        String type = root.path("type").asText(null);
        if (StringUtils.isBlank(type)
                || "action".equals(type)
                || "final".equals(type)
                || root.has("tool")
                || visibleTools == null
                || !visibleTools.contains(type)) {
            return root;
        }
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("type", "action");
        normalized.put("tool", type);
        root.fields().forEachRemaining(field -> {
            if (!"type".equals(field.getKey())) {
                normalized.set(field.getKey(), field.getValue());
            }
        });
        return normalized;
    }

    // ------------------------------------------------------------------
    // Field extraction
    // ------------------------------------------------------------------

    private AgentDecision buildDecision(JsonNode root) throws DecisionParseException {
        String type = root.path("type").asText(null);

        // --- Type inference: common model output patterns without explicit type ---
        if (StringUtils.isBlank(type)) {
            boolean hasTool = !root.path("tool").isMissingNode()
                    && StringUtils.isNotBlank(root.path("tool").asText(null));
            boolean hasInput = !root.path("input").isMissingNode()
                    && !root.path("input").isNull();
            boolean hasAnswer = !root.path("answer").isMissingNode()
                    && StringUtils.isNotBlank(root.path("answer").asText(null));

            if (hasTool && hasInput) {
                // Infer action when tool+input are present but type is missing
                type = "action";
            } else if (hasAnswer) {
                // Infer final when answer is present but type is missing
                type = "final";
            } else {
                throw new DecisionParseException(
                        DecisionParseErrorCode.MISSING_TYPE,
                        "type 字段不能为空，必须是 \"action\" 或 \"final\"",
                        null,
                        Map.of("expectedType", "action | final",
                                "example", "{\"type\":\"action\",\"tool\":\"read_file\",\"input\":{\"path\":\"src/App.java\"}}"));
            }
        }

        type = type.trim().toLowerCase();
        if (!"action".equals(type) && !"final".equals(type)) {
            throw new DecisionParseException(
                    DecisionParseErrorCode.INVALID_TYPE,
                    "type 只能是 \"action\" 或 \"final\"，收到: \"" + type + "\"",
                    null,
                    Map.of("receivedType", type,
                            "validTypes", List.of("action", "final")));
        }

        JsonNode input = root.path("input");
        if (!input.isMissingNode() && !input.isNull() && !input.isObject()) {
            throw new DecisionParseException(
                    DecisionParseErrorCode.NON_OBJECT_INPUT,
                    "input 必须是 JSON 对象");
        }
        Map<String, Object> inputView = input.isMissingNode() || input.isNull()
                ? Map.of()
                : objectMapper.convertValue(input, new TypeReference<Map<String, Object>>() {});

        String rawReason = root.path("reason").asText(null);
        String reason = null;
        if (StringUtils.isNotBlank(rawReason)) {
            String trimmed = rawReason.trim();
            reason = trimmed.length() > 240 ? trimmed.substring(0, 240) : trimmed;
        }

        // For action type, tool is required
        String tool = root.path("tool").asText(null);
        if ("action".equals(type) && StringUtils.isBlank(tool)) {
            throw new DecisionParseException(
                    DecisionParseErrorCode.MISSING_TOOL,
                    "action.tool 不能为空",
                    null,
                    Map.of("expectedField", "tool",
                            "example", "read_file"));
        }

        return AgentDecision.builder()
                .type(type)
                .thought(root.path("thought").asText(null))
                .reason(reason)
                .tool(tool)
                .input(input)
                .inputView(inputView)
                .answer(root.path("answer").asText(null))
                .evidence(root.path("evidence").isArray()
                        ? objectMapper.convertValue(
                                root.path("evidence"),
                                new TypeReference<List<Map<String, Object>>>() {})
                        : List.of())
                .build();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Strip markdown code fences: leading ```json, trailing ```.
     * Handles multiple layers of fencing (common when models nest code blocks).
     */
    String stripMarkdownFence(String text) {
        String result = StringUtils.trimToEmpty(text);
        // Strip opening fence: ```json, ```JSON, ```, etc.
        while (result.startsWith("```")) {
            result = result.replaceFirst("^```[a-zA-Z]*\\s*", "").trim();
        }
        // Strip closing fence
        while (result.endsWith("```")) {
            result = result.replaceFirst("\\s*```$", "").trim();
        }
        return result;
    }

    private boolean isValidJson(String text) {
        try {
            objectMapper.readTree(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String truncateForError(String text) {
        if (text == null) return "";
        return text.length() > 500 ? text.substring(0, 500) + "..." : text;
    }
}
