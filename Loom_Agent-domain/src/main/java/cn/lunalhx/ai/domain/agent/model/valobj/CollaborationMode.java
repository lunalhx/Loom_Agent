package cn.lunalhx.ai.domain.agent.model.valobj;

import java.util.Locale;

/** Durable collaboration mode copied into each root Run at start. */
public enum CollaborationMode {
    BUILD,
    PLAN;

    public static CollaborationMode parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("mode must be build or plan");
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "BUILD" -> BUILD;
            case "PLAN" -> PLAN;
            default -> throw new IllegalArgumentException("mode must be build or plan: " + value);
        };
    }

    public String cliName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
