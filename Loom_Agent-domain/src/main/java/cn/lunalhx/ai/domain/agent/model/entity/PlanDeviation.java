package cn.lunalhx.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent-provided report for terminating a Plan-bound Build Run.
 *
 * <p>Run identity, Plan binding identity, and terminal metadata remain
 * Runtime-owned and are deliberately not part of this value.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanDeviation {

    public static boolean isSupportedConflictKind(String kind) {
        return "objective".equals(kind)
                || "scope".equals(kind)
                || "architectural_decision".equals(kind)
                || "validation_requirement".equals(kind);
    }

    public static boolean isSupportedOperation(String operation) {
        return "created".equals(operation)
                || "modified".equals(operation)
                || "deleted".equals(operation);
    }

    public static boolean isValidWorkspacePath(String path) {
        if (path == null || path.isBlank() || !path.equals(path.strip())
                || path.startsWith("/") || path.startsWith("\\")
                || path.matches("^[A-Za-z]:[\\\\/].*")) {
            return false;
        }
        for (String segment : path.replace('\\', '/').split("/")) {
            if ("..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private Conflict conflict;
    private List<WorkspaceChange> workspaceChanges;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Conflict {
        private String kind;
        private String summary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkspaceChange {
        private String path;
        private String operation;
        private String summary;
    }
}
