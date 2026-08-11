package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.PlanDeviation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loom XML decision parser mirroring loom-code {@code LoomCode.parse}.
 *
 * <p>Parse priority (fixed):
 * <ol>
 *   <li>Plan Deviation and Plan Submission terminal actions</li>
 *   <li>{@code <tool>{...json...}</tool>} — JSON object with name/args</li>
 *   <li>XML-style tool with attributes and child tags</li>
 *   <li>{@code <final>...</final>}</li>
 *   <li>Non-blank bare text as final</li>
 *   <li>Empty text or malformed structure -> RETRY (format retry, no tool step)</li>
 * </ol>
 *
 * <p>Terminal action markers are resolved before ordinary tool/final parsing;
 * the {@code tool} kind is decided before {@code final} otherwise, matching
 * loom-code's tag-order comparison.
 */
final class DecisionParser {

    private static final Pattern XML_TOOL_PATTERN =
            Pattern.compile("<tool(?<attrs>[^>]*)>(?<body>.*?)</tool>", Pattern.DOTALL);
    private static final Pattern ATTR_PATTERN =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern FINAL_PATTERN =
            Pattern.compile("<final>(.*?)</final>", Pattern.DOTALL);
    private static final String PLAN_SUBMISSION_OPEN = "<plan_submission>";
    private static final String PLAN_SUBMISSION_CLOSE = "</plan_submission>";
    private static final String PLAN_DEVIATION_OPEN = "<plan_deviation>";
    private static final String PLAN_DEVIATION_CLOSE = "</plan_deviation>";
    private static final Set<String> PLAN_SUBMISSION_FIELDS =
            Set.of("title", "body", "dependencies");
    private static final Set<String> PLAN_DEVIATION_FIELDS =
            Set.of("conflict", "workspace_changes");
    private static final Set<String> PLAN_DEVIATION_CONFLICT_FIELDS =
            Set.of("kind", "summary");
    private static final Set<String> PLAN_DEVIATION_CHANGE_FIELDS =
            Set.of("path", "operation", "summary");

    private final ObjectMapper objectMapper;

    public DecisionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parse a raw model output string into an {@link AgentDecision}.
     *
     * @param rawOutput the raw text emitted by the model
     * @return the parsed decision (type tool/final/plan_submission/plan_deviation), or a retry decision when
     *         the output is empty or structurally invalid
     * @throws DecisionParseException if the output cannot be parsed at all
     */
    public AgentDecision parse(String rawOutput) throws DecisionParseException {
        return parse(rawOutput, List.of());
    }

    public AgentDecision parse(String rawOutput, List<String> visibleTools) throws DecisionParseException {
        String raw = String.valueOf(rawOutput);
        String text = raw == null ? "" : raw;
        String stripped = text.strip();

        // 1. Plan Deviation is an exact terminal action and takes priority
        // over every other protocol form. Inspect the outer wrapper first so
        // marker-like strings inside a payload remain data.
        if (isExactPlanDeviationWrapper(stripped)) {
            return parsePlanDeviation(text);
        }
        // 2. Plan Submission remains the next exact terminal action. This
        // check must happen before marker detection so literal tags inside a
        // valid JSON string remain payload data.
        if (isExactPlanWrapper(stripped)) {
            return parsePlanSubmission(text);
        }
        if (hasPlanDeviationMarker(text)
                && !isExactToolWrapper(stripped)
                && !isExactFinalWrapper(stripped)) {
            return retry("model returned an invalid plan deviation wrapper");
        }
        if (hasPlanSubmissionMarker(text)
                && !isExactToolWrapper(stripped)
                && !isExactFinalWrapper(stripped)) {
            return retry("model returned an invalid plan submission wrapper");
        }

        // 3. <tool> JSON object (takes priority over <final> like loom-code)
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

        // 4. XML-style tool with attributes / child tags
        if (text.contains("<tool") && isBefore(text, "<tool", "<final>")) {
            Map<String, Object> xmlPayload = parseXmlTool(text);
            if (xmlPayload != null) {
                return toolDecision(
                        String.valueOf(xmlPayload.get("name")),
                        (JsonNode) xmlPayload.get("args"));
            }
            return retry();
        }

        // 5. <final>...</final>
        if (text.contains("<final>")) {
            String finalText = extract(text, "final");
            if (!finalText.isBlank()) {
                return finalDecision(finalText);
            }
            return retry("model returned an empty <final> answer");
        }

        // 6. Non-blank bare text as final
        if (!stripped.isEmpty()) {
            return finalDecision(stripped);
        }

        // 7. Empty -> retry
        return retry("model returned an empty response");
    }

    private AgentDecision parsePlanDeviation(String text) {
        String stripped = text.strip();
        String body = stripped.substring(
                PLAN_DEVIATION_OPEN.length(),
                stripped.length() - PLAN_DEVIATION_CLOSE.length()).strip();
        JsonNode payload;
        try (JsonParser parser = objectMapper.getFactory().createParser(body)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            payload = objectMapper.readTree(parser);
            if (parser.nextToken() != null) {
                return retry("plan deviation payload must contain exactly one JSON value");
            }
        } catch (Exception e) {
            return retry("model returned malformed plan deviation JSON");
        }
        if (payload == null || !payload.isObject()) {
            return retry("plan deviation payload must be a JSON object");
        }
        if (!fieldNames(payload).equals(PLAN_DEVIATION_FIELDS)) {
            return retry("plan deviation payload must contain exactly conflict and workspace_changes");
        }

        JsonNode conflict = payload.get("conflict");
        if (conflict == null || !conflict.isObject()
                || !fieldNames(conflict).equals(PLAN_DEVIATION_CONFLICT_FIELDS)) {
            return retry("plan deviation conflict must contain exactly kind and summary");
        }
        JsonNode kind = conflict.get("kind");
        JsonNode conflictSummary = conflict.get("summary");
        if (kind == null || !kind.isTextual()
                || !PlanDeviation.isSupportedConflictKind(kind.asText())
                || conflictSummary == null || !conflictSummary.isTextual()
                || conflictSummary.asText().isBlank()) {
            return retry("plan deviation conflict kind or summary is invalid");
        }

        JsonNode changes = payload.get("workspace_changes");
        if (changes == null || !changes.isArray()) {
            return retry("plan deviation workspace_changes must be an array");
        }
        List<PlanDeviation.WorkspaceChange> workspaceChanges = new ArrayList<>();
        for (JsonNode change : changes) {
            if (change == null || !change.isObject()
                    || !fieldNames(change).equals(PLAN_DEVIATION_CHANGE_FIELDS)) {
                return retry("plan deviation workspace changes have invalid fields");
            }
            JsonNode path = change.get("path");
            JsonNode operation = change.get("operation");
            JsonNode summary = change.get("summary");
            if (path == null || !path.isTextual()
                    || !PlanDeviation.isValidWorkspacePath(path.asText())
                    || operation == null || !operation.isTextual()
                    || !PlanDeviation.isSupportedOperation(operation.asText())
                    || summary == null || !summary.isTextual() || summary.asText().isBlank()) {
                return retry("plan deviation workspace change is invalid");
            }
            workspaceChanges.add(PlanDeviation.WorkspaceChange.builder()
                    .path(path.asText())
                    .operation(operation.asText())
                    .summary(summary.asText())
                    .build());
        }

        return AgentDecision.builder()
                .type("plan_deviation")
                .planDeviation(PlanDeviation.builder()
                        .conflict(PlanDeviation.Conflict.builder()
                                .kind(kind.asText())
                                .summary(conflictSummary.asText())
                                .build())
                        .workspaceChanges(workspaceChanges)
                        .build())
                .build();
    }

    private Set<String> fieldNames(JsonNode object) {
        Set<String> fields = new HashSet<>();
        object.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private AgentDecision parsePlanSubmission(String text) {
        String stripped = text.strip();
        if (!stripped.startsWith(PLAN_SUBMISSION_OPEN)
                || !stripped.endsWith(PLAN_SUBMISSION_CLOSE)) {
            return retry("model returned an invalid plan submission wrapper");
        }

        String body = stripped.substring(
                PLAN_SUBMISSION_OPEN.length(),
                stripped.length() - PLAN_SUBMISSION_CLOSE.length()).strip();
        JsonNode payload;
        try (JsonParser parser = objectMapper.getFactory().createParser(body)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            payload = objectMapper.readTree(parser);
            if (parser.nextToken() != null) {
                return retry("plan submission payload must contain exactly one JSON value");
            }
        } catch (Exception e) {
            return retry("model returned malformed plan submission JSON");
        }
        if (payload == null || !payload.isObject()) {
            return retry("plan submission payload must be a JSON object");
        }

        Set<String> fields = new HashSet<>();
        payload.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(PLAN_SUBMISSION_FIELDS)) {
            return retry("plan submission payload must contain exactly title, body, and dependencies");
        }

        JsonNode title = payload.get("title");
        JsonNode planBody = payload.get("body");
        JsonNode dependencies = payload.get("dependencies");
        if (title == null || !title.isTextual() || title.asText().isBlank()
                || planBody == null || !planBody.isTextual() || planBody.asText().isBlank()
                || dependencies == null || !dependencies.isArray()) {
            return retry("plan submission title/body must be non-blank strings and dependencies must be an array");
        }
        List<String> dependencyValues = new ArrayList<>();
        for (JsonNode dependency : dependencies) {
            if (dependency == null || !dependency.isTextual()) {
                return retry("plan submission dependencies must contain only strings");
            }
            dependencyValues.add(dependency.asText());
        }

        return AgentDecision.builder()
                .type("plan_submission")
                .planSubmission(cn.lunalhx.ai.domain.agent.model.entity.PlanSubmission.builder()
                        .title(title.asText())
                        .body(planBody.asText())
                        .dependencies(dependencyValues)
                        .build())
                .build();
    }

    private boolean hasPlanSubmissionMarker(String text) {
        return text.contains("<plan_submission") || text.contains("</plan_submission>");
    }

    private boolean hasPlanDeviationMarker(String text) {
        return text.contains("<plan_deviation") || text.contains("</plan_deviation>");
    }

    private boolean isExactPlanDeviationWrapper(String text) {
        return text.startsWith(PLAN_DEVIATION_OPEN)
                && text.endsWith(PLAN_DEVIATION_CLOSE);
    }

    private boolean isExactPlanWrapper(String text) {
        return text.startsWith(PLAN_SUBMISSION_OPEN)
                && text.endsWith(PLAN_SUBMISSION_CLOSE);
    }

    private boolean isExactToolWrapper(String text) {
        return (text.startsWith("<tool>") || text.startsWith("<tool "))
                && text.endsWith("</tool>");
    }

    private boolean isExactFinalWrapper(String text) {
        return text.startsWith("<final>") && text.endsWith("</final>");
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
