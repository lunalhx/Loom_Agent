package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.skill.model.SkillCatalogLimits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Metadata-only SKILL.md frontmatter reader; it never executes package content. */
final class SkillFrontmatterParser {
    private static final Set<String> SUPPORTED_FIELDS = Set.of(
            "name", "description", "license", "compatibility", "metadata",
            "disable-model-invocation", "user-invocable");
    private static final Set<String> COMPATIBILITY_ONLY_FIELDS = Set.of(
            "allowed-tools", "disallowed-tools", "context", "agent", "model");

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public ParsedFrontmatter parse(String directoryName, byte[] skillMdBytes) {
        List<String> validationErrors = new ArrayList<>();
        List<String> compatibilityDiagnostics = new ArrayList<>();
        if (skillMdBytes.length > SkillCatalogLimits.MAX_SKILL_MD_BYTES) {
            validationErrors.add("SKILL.md exceeds " + SkillCatalogLimits.MAX_SKILL_MD_BYTES + " bytes");
            return invalid(directoryName, validationErrors, compatibilityDiagnostics);
        }
        String text = new String(skillMdBytes, StandardCharsets.UTF_8);
        if (!text.startsWith("---")) {
            validationErrors.add("SKILL.md missing YAML frontmatter");
            return invalid(directoryName, validationErrors, compatibilityDiagnostics);
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            validationErrors.add("SKILL.md frontmatter is not closed");
            return invalid(directoryName, validationErrors, compatibilityDiagnostics);
        }
        String frontmatter = text.substring(3, end).strip();
        JsonNode root;
        try {
            root = yaml.readTree(frontmatter);
        } catch (Exception e) {
            validationErrors.add("invalid YAML frontmatter: " + e.getMessage());
            return invalid(directoryName, validationErrors, compatibilityDiagnostics);
        }
        if (root == null || !root.isObject()) {
            validationErrors.add("SKILL.md frontmatter must be a YAML mapping");
            return invalid(directoryName, validationErrors, compatibilityDiagnostics);
        }
        root.fieldNames().forEachRemaining(field -> {
            if (COMPATIBILITY_ONLY_FIELDS.contains(field)) {
                compatibilityDiagnostics.add("unsupported Claude field ignored: " + field);
            } else if (!SUPPORTED_FIELDS.contains(field)) {
                validationErrors.add("unsupported frontmatter field: " + field);
            }
        });
        if (!validationErrors.isEmpty()) {
            return invalid(directoryName, validationErrors, compatibilityDiagnostics);
        }
        String name = requiredText(root, "name", validationErrors);
        String description = requiredText(root, "description", validationErrors);
        if (name != null && !isValidName(name)) {
            validationErrors.add("name must match [a-z0-9-]{1," + SkillCatalogLimits.MAX_NAME_LENGTH + "}");
        }
        if (name != null && name.length() > SkillCatalogLimits.MAX_NAME_LENGTH) {
            validationErrors.add("name exceeds " + SkillCatalogLimits.MAX_NAME_LENGTH + " characters");
        }
        if (description != null && description.isBlank()) {
            validationErrors.add("description must be non-empty");
        }
        if (description != null && description.length() > SkillCatalogLimits.MAX_DESCRIPTION_LENGTH) {
            validationErrors.add("description exceeds " + SkillCatalogLimits.MAX_DESCRIPTION_LENGTH + " characters");
        }
        if (name != null && !directoryName.equals(name)) {
            validationErrors.add("name must match directory " + directoryName);
        }
        if (!validationErrors.isEmpty()) {
            return invalid(directoryName, validationErrors, compatibilityDiagnostics);
        }
        boolean userInvocable = !root.has("user-invocable") || root.path("user-invocable").asBoolean(true);
        boolean modelInvocable = !root.has("disable-model-invocation")
                || !root.path("disable-model-invocation").asBoolean(false);
        String license = optionalText(root, "license");
        String compatibility = optionalNodeText(root, "compatibility");
        String metadata = optionalNodeText(root, "metadata");
        return new ParsedFrontmatter(true, name, description, license, compatibility, metadata,
                userInvocable, modelInvocable,
                List.copyOf(new LinkedHashSet<>(compatibilityDiagnostics)),
                List.of());
    }

    private static ParsedFrontmatter invalid(String directoryName, List<String> validationErrors,
                                             List<String> compatibilityDiagnostics) {
        return new ParsedFrontmatter(false, directoryName, null, null, null, null,
                true, true,
                List.copyOf(compatibilityDiagnostics), List.copyOf(validationErrors));
    }

    private static String requiredText(JsonNode root, String field, List<String> validationErrors) {
        JsonNode node = root.get(field);
        if (node == null || !node.isValueNode() || node.asText("").isBlank()) {
            validationErrors.add(field + " is required");
            return null;
        }
        return node.asText().strip();
    }

    private static String optionalText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isValueNode() || node.asText("").isBlank()) {
            return null;
        }
        return node.asText().strip();
    }

    private static String optionalNodeText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || (node.isValueNode() && node.asText("").isBlank())) {
            return null;
        }
        return node.isValueNode() ? node.asText().strip() : node.toString();
    }

    private static boolean isValidName(String name) {
        if (name.isEmpty() || name.length() > SkillCatalogLimits.MAX_NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '-') {
                return false;
            }
        }
        return true;
    }

    public record ParsedFrontmatter(
            boolean valid,
            String name,
            String description,
            String license,
            String compatibility,
            String metadata,
            boolean userInvocable,
            boolean modelInvocable,
            List<String> compatibilityDiagnostics,
            List<String> validationErrors) {
    }
}
