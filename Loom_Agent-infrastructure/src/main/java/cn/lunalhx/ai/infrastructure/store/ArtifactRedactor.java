package cn.lunalhx.ai.infrastructure.store;

import cn.lunalhx.ai.domain.agent.service.context.SanitizationPolicy;
import cn.lunalhx.ai.domain.agent.service.context.SecretRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Last-line-of-defense redaction for file writers (trace, run store,
 * checkpoint, session, memory). The state layer already sanitizes; this
 * boundary guarantees no artifact leaves the process with a secret even if a
 * future code path forgets. It never replaces the state-layer sanitizer.
 *
 * <p>Reports whether redaction actually happened and the applied rules
 * version so writers can mark {@code sensitiveRedacted}/{@code redactionVersion}
 * truthfully instead of hard-coding {@code false}.
 */
public final class ArtifactRedactor {

    private final SecretRedactor redactor;

    public ArtifactRedactor(SecretRedactor redactor) {
        this.redactor = redactor;
    }

    public ArtifactRedactor() {
        this(SecretRedactor.none());
    }

    public String redact(String value) {
        return redactor.redact(value);
    }

    public String redactionVersion() {
        return String.valueOf(SanitizationPolicy.RULES_VERSION);
    }

    /** Redact a JSON tree in place; returns whether any value was changed. */
    public boolean redactTree(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isContainerNode()) {
            return false;
        }
        boolean changed = false;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            java.util.Iterator<String> fields = obj.fieldNames();
            java.util.List<String> names = new java.util.ArrayList<>();
            fields.forEachRemaining(names::add);
            for (String name : names) {
                JsonNode child = obj.get(name);
                if (child.isContainerNode()) {
                    changed |= redactTree(child);
                } else if (child.isTextual()) {
                    String redacted = redact(child.asText());
                    if (!redacted.equals(child.asText())) {
                        obj.put(name, redacted);
                        changed = true;
                    }
                } else if (child.isValueNode() && SecretRedactor.isSensitiveName(name)
                        && !child.isNull()) {
                    obj.put(name, SanitizationPolicy.PLACEHOLDER);
                    changed = true;
                }
            }
            return changed;
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (JsonNode child : arr) {
                changed |= redactTree(child);
            }
            return changed;
        }
        return false;
    }

    /** Redact in place and stamp the version/flag on the given event object.
     *  Mutates {@code event} only through its setters; returns the same event. */
    public cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent stamp(
            cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent event) {
        if (event == null) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode tree =
                new ObjectMapper().valueToTree(event);
        boolean changed = redactTree(tree);
        event.setSensitiveRedacted(changed);
        event.setRedactionVersion(redactionVersion());
        return event;
    }

    /** Serialize an object to a redacted JSON tree. */
    public JsonNode toRedactedTree(ObjectMapper mapper, Object value) {
        JsonNode tree = mapper.valueToTree(value);
        redactTree(tree);
        return tree;
    }
}
