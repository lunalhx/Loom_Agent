package cn.lunalhx.ai.domain.skill.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Independent {@code $skill-name} selector: appearance-order dedupe and removal from task text.
 */
final class SkillSelectorParser {
    private static final Pattern SELECTOR = Pattern.compile("\\$([a-z0-9]+(?:-[a-z0-9]+)*)");

    public ParsedSelectors parse(String taskText) {
        String text = Objects.requireNonNullElse(taskText, "");
        Matcher matcher = SELECTOR.matcher(text);
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        StringBuilder remainder = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            ordered.add(matcher.group(1));
            remainder.append(text, last, matcher.start());
            last = matcher.end();
        }
        remainder.append(text.substring(last));
        String cleaned = remainder.toString().replaceAll("[ \\t\\x0B\\f]+", " ").strip();
        return new ParsedSelectors(List.copyOf(ordered), cleaned, !ordered.isEmpty());
    }

    public record ParsedSelectors(List<String> namesInOrder, String taskWithoutSelectors, boolean hadSelectors) {
        public ParsedSelectors {
            namesInOrder = namesInOrder == null ? List.of() : List.copyOf(namesInOrder);
            taskWithoutSelectors = taskWithoutSelectors == null ? "" : taskWithoutSelectors;
        }

        public List<String> names() {
            return namesInOrder;
        }
    }
}
