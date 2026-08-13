package cn.lunalhx.ai.domain.tool.service;

import java.util.Set;

/** File-mutating tools that can be reconciled from current Repository State. */
public final class FileMutationTools {

    public static final Set<String> NAMES = Set.of("write_file", "patch_file");

    private FileMutationTools() {
    }

    public static boolean isFileMutation(String toolName) {
        return toolName != null && NAMES.contains(toolName);
    }
}
