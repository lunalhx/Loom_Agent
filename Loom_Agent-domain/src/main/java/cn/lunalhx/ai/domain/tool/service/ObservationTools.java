package cn.lunalhx.ai.domain.tool.service;

import java.util.Set;

/** Observation tools whose durable results are re-observable Repository State. */
public final class ObservationTools {

    public static final Set<String> NAMES = Set.of("read_file", "list_files", "search");

    private ObservationTools() {
    }

    public static boolean isObservation(String toolName) {
        return toolName != null && NAMES.contains(toolName);
    }
}
