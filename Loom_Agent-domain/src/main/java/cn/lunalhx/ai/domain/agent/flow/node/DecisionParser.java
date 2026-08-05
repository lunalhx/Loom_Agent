package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loom XML decision parser mirroring loom-code {@code LoomCode.parse}.
 *
 * <p>Parse priority (fixed):
 * <ol>
 *   <li>{@code <tool>{...json...}</tool>} — JSON object with name/args</li>
 *   <li>XML-style tool with attributes and child tags</li>
 *   <li>{@code <final>...</final>}</li>
 *   <li>Non-blank bare text as final</li>
 *   <li>Empty text or malformed structure -> RETRY (format retry, no tool step)</li>
 * </ol>
 *
 * <p>The {@code tool} kind is decided before {@code final} regardless of
 * position, matching loom-code's tag-order comparison.
 */
final class DecisionParser {

    private static final Pattern XML_TOOL_PATTERN =
            Pattern.compile("<tool(?<attrs>[^>]*)>(?<body>.*?)</tool>", Pattern.DOTALL);
    private static final Pattern ATTR_PATTERN =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern FINAL_PATTERN =
            Pattern.compile("<final>(.*?)</final>", Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    public DecisionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parse a raw model output string into an {@link AgentDecision}.
     *
     * @param rawOutput the raw text emitted by the model
     * @return the parsed decision (type tool/final), or a retry decision when
     *         the output is empty or structurally invalid
     * @throws DecisionParseException if the output cannot be parsed at all
     */
    public AgentDecision parse(String rawOutput) throws DecisionParseException {
        return parse(rawOutput, List.of());
    }

    public AgentDecision parse(String rawOutput, List<String> visibleTools) throws DecisionParseException {
        String raw = String.valueOf(rawOutput);
        String text = raw == null ? "" : raw;

        // 1. <tool> JSON object (takes priority over <final> like loom-code)
        if (text.contains("<tool>") && (isBefore(text, "<tool>", "<final>"))) {
            String body = extract(text, "tool");
            JsonNode payload;
            try {
                payload = objectMapper.readTree(body);
            } catch (Exception e) {
                return retry("model returned malformed tool JSON");
            }
            if (payload == null || !payload.isObject()) {
                return retry("tool payload must be a JSON object");
            }
            String name = payload.path("name").asText("").strip();
            if (name.isEmpty()) {
                return retry("tool payload is missing a tool name");
            }
            JsonNode args = payload.path("args");
            if (args == null || args.isMissingNode() || args.isNull()) {
                args = objectMapper.createObjectNode();
            }
            if (!args.isObject()) {
                return retry();
            }
            return toolDecision(name, args);
        }

        // 2. XML-style tool with attributes / child tags
        if (text.contains("<tool") && isBefore(text, "<tool", "<final>")) {
            Map<String, Object> xmlPayload = parseXmlTool(text);
            if (xmlPayload != null) {
                return toolDecision(
                        String.valueOf(xmlPayload.get("name")),
                        (JsonNode) xmlPayload.get("args"));
            }
            return retry();
        }

        // 3. <final>...</final>
        if (text.contains("<final>")) {
            String finalText = extract(text, "final");
            if (!finalText.isBlank()) {
                return finalDecision(finalText);
            }
            return retry("model returned an empty <final> answer");
        }

        // 4. Non-blank bare text as final
        String stripped = text.strip();
        if (!stripped.isEmpty()) {
            return finalDecision(stripped);
        }

        // 5. Empty -> retry
        return retry("model returned an empty response");
    }

    private AgentDecision toolDecision(String name, JsonNode args) {
        Map<String, Object> inputView = objectMapper.convertValue(
                args, new TypeReference<Map<String, Object>>() {});
        return AgentDecision.builder()
                .type("action")
                .tool(name)
                .input(args)
                .inputView(inputView)
                .build();
    }

    private AgentDecision finalDecision(String answer) {
        return AgentDecision.builder()
                .type("final")
                .answer(answer)
                .build();
    }

    private AgentDecision retry() {
        return retry(null);
    }

    private AgentDecision retry(String problem) {
        String prefix = "Runtime notice";
        if (problem != null && !problem.isBlank()) {
            prefix += ": " + problem;
        } else {
            prefix += ": model returned malformed tool output";
        }
        String message = prefix
                + ". Reply with a valid <tool> call or a non-empty <final> answer. "
                + "For multi-line files, prefer <tool name=\"write_file\" path=\"file.py\"><content>...</content></tool>.";
        return AgentDecision.builder()
                .type("retry")
                .answer(message)
                .build();
    }

    private boolean isBefore(String text, String first, String second) {
        int firstIndex = text.indexOf(first);
        if (firstIndex == -1) {
            return false;
        }
        int secondIndex = text.indexOf(second);
        return secondIndex == -1 || firstIndex < secondIndex;
    }

    private String extract(String text, String tag) {
        String startTag = "<" + tag + ">";
        String endTag = "</" + tag + ">";
        int start = text.indexOf(startTag);
        if (start == -1) {
            return text;
        }
        start += startTag.length();
        int end = text.indexOf(endTag, start);
        if (end == -1) {
            return text.substring(start).strip();
        }
        return text.substring(start, end).strip();
    }

    private String extractRaw(String text, String tag) {
        String startTag = "<" + tag + ">";
        String endTag = "</" + tag + ">";
        int start = text.indexOf(startTag);
        if (start == -1) {
            return text;
        }
        start += startTag.length();
        int end = text.indexOf(endTag, start);
        if (end == -1) {
            return text.substring(start);
        }
        return text.substring(start, end);
    }

    private Map<String, Object> parseXmlTool(String raw) {
        Matcher matcher = XML_TOOL_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        String attrsText = matcher.group("attrs");
        Map<String, String> attrs = parseAttrs(attrsText);
        String name = attrs.getOrDefault("name", "").strip();
        if (name.isEmpty()) {
            return null;
        }
        String body = matcher.group("body");
        Map<String, Object> args = new LinkedHashMap<>(attrs);
        args.remove("name");
        for (String key : List.of("content", "old_text", "new_text", "command", "task", "pattern", "path")) {
            if (body.contains("<" + key + ">")) {
                args.put(key, extractRaw(body, key));
            }
        }
        String bodyText = body.strip();
        if ("write_file".equals(name) && !args.containsKey("content") && !bodyText.isEmpty()) {
            args.put("content", bodyText);
        }
        if ("delegate".equals(name) && !args.containsKey("task") && !bodyText.isEmpty()) {
            args.put("task", bodyText.strip());
        }
        ObjectNode argsNode = objectMapper.valueToTree(coerceXmlValues(args));
        return Map.of("name", name, "args", (Object) argsNode);
    }

    /**
     * XML attributes are strings; integer-looking values are lifted to JSON
     * numbers so integer-typed schema fields accept them (mirrors loom-code
     * semantic validators calling {@code int(args.get("start", 1))}).
     */
    private Map<String, Object> coerceXmlValues(Map<String, Object> args) {
        Map<String, Object> coerced = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s) {
                try {
                    coerced.put(entry.getKey(), Long.parseLong(s));
                    continue;
                } catch (NumberFormatException ignored) {
                    // keep as string
                }
            }
            coerced.put(entry.getKey(), value);
        }
        return coerced;
    }

    private Map<String, String> parseAttrs(String text) {
        Map<String, String> attrs = new LinkedHashMap<>();
        Matcher matcher = ATTR_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            attrs.put(matcher.group(1),
                    matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
        }
        return attrs;
    }
}
