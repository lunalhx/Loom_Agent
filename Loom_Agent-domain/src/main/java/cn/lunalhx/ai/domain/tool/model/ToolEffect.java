package cn.lunalhx.ai.domain.tool.model;

/** Composable state effects a tool invocation may still produce. */
public enum ToolEffect {
    REPOSITORY_READ,
    DISPOSABLE_WRITE,
    REPOSITORY_MUTATION,
    EXTERNAL_READ,
    EXTERNAL_MUTATION
}
