package cn.lunalhx.ai.domain.tool.model;

import java.util.List;

/** Normalized, non-secret match subject used by one permission evaluator. */
public record PermissionSubject(String toolName, String exactKey, List<String> shellUnits,
                                boolean opaque, List<String> paths, List<String> domains) {
    public PermissionSubject {
        toolName = toolName == null ? "" : toolName;
        exactKey = exactKey == null ? "" : exactKey;
        shellUnits = shellUnits == null ? List.of() : List.copyOf(shellUnits);
        paths = paths == null ? List.of() : List.copyOf(paths);
        domains = domains == null ? List.of() : List.copyOf(domains);
    }
}
