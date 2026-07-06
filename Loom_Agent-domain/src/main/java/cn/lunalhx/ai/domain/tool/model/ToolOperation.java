package cn.lunalhx.ai.domain.tool.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ToolOperation {

    private static final Set<String> READ_TOOLS = Set.of(
            "read_file", "code_search", "find_files", "list_dir");
    private static final Set<String> WORKSPACE_WRITE_TOOLS = Set.of(
            "write_file", "replace_in_file", "delete_files");

    private ToolOperation() {
    }

    public static boolean isRead(String tool) {
        return READ_TOOLS.contains(tool);
    }

    public static boolean isWorkspaceWrite(String tool) {
        return WORKSPACE_WRITE_TOOLS.contains(tool);
    }

    public static List<String> inputPaths(JsonNode input) {
        if (input == null || input.isNull() || input.isMissingNode()) {
            return List.of();
        }
        String path = input.path("path").asText(null);
        if (StringUtils.isBlank(path)) {
            path = input.path("filePath").asText(null);
        }
        if (StringUtils.isNotBlank(path)) {
            return List.of(normalizePath(path));
        }
        List<String> paths = new ArrayList<>();
        if (input.path("paths").isArray()) {
            input.path("paths").forEach(item -> {
                if (StringUtils.isNotBlank(item.asText())) {
                    paths.add(normalizePath(item.asText()));
                }
            });
        }
        return List.copyOf(paths);
    }

    public static String normalizePath(String path) {
        String normalized = StringUtils.trimToEmpty(path).replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        if (normalized.isEmpty() || normalized.startsWith("/")) {
            throw new IllegalArgumentException("目标路径必须是工作区相对路径");
        }
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("目标路径不能包含 ..");
            }
        }
        return normalized;
    }
}
