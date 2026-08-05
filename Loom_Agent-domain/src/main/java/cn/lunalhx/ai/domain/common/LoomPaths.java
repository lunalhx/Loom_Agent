package cn.lunalhx.ai.domain.common;

import java.nio.file.Path;
import java.util.Map;

public final class LoomPaths {

    private final Path home;
    private final Path startupWorkspace;
    private final Path userHome;

    public LoomPaths(Path home) {
        this(home, Path.of(System.getProperty("user.dir", ".")),
                Path.of(System.getProperty("user.home")));
    }

    public LoomPaths(Path home, Path startupWorkspace) {
        this(home, startupWorkspace, Path.of(System.getProperty("user.home")));
    }

    public LoomPaths(Path home, Path startupWorkspace, Path userHome) {
        this.home = home.toAbsolutePath().normalize();
        this.startupWorkspace = startupWorkspace.toAbsolutePath().normalize();
        this.userHome = userHome.toAbsolutePath().normalize();
    }

    public static LoomPaths system() {
        Map<String, String> env = System.getenv();
        String configured = System.getProperty("loom.data-dir");
        if (configured == null || configured.isBlank()) {
            configured = env.get("LOOM_DATA_DIR");
        }
        Path home = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".loom-agent")
                : Path.of(configured);
        String workspace = System.getProperty("loom.workspace-root");
        if (workspace == null || workspace.isBlank()) {
            workspace = env.get("AGENT_WORKSPACE_ROOT");
        }
        return new LoomPaths(home, workspace == null || workspace.isBlank()
                ? Path.of(System.getProperty("user.dir", ".")) : Path.of(workspace),
                Path.of(System.getProperty("user.home")));
    }

    public Path home() { return home; }
    public Path startupWorkspace() { return startupWorkspace; }
    public Path userHome() { return userHome; }
    public Path runtimeConfig() { return runtimeConfig(startupWorkspace); }
    public Path runtimeConfig(Path workspace) { return workspace.resolve(".loom/agent-runtime.yml").normalize(); }
    public Path extensionsConfig() { return extensionsConfig(startupWorkspace); }
    public Path extensionsConfig(Path workspace) { return workspace.resolve(".loom/loom-extensions.json").normalize(); }
    public Path backgroundTasks() { return home.resolve("background-tasks"); }
    public Path database() { return home.resolve("loom-agent.db"); }
    public Path contextArtifacts() { return home.resolve("context-artifacts"); }
    public Path conversationRoot(String conversationId) { return home.resolve("conversations").resolve(safeId(conversationId)); }
    public Path sessionTemp(String conversationId) { return conversationRoot(conversationId).resolve("tmp"); }
    public Path uploads(String conversationId) { return conversationRoot(conversationId).resolve("uploads"); }
    public Path outputs(String conversationId) { return conversationRoot(conversationId).resolve("outputs"); }
    public Path sandbox(String conversationId) { return conversationRoot(conversationId).resolve("sandbox"); }

    public Path resolveWorkspacePath(String configured, Path defaultPath) {
        if (configured == null || configured.isBlank()) {
            return defaultPath.toAbsolutePath().normalize();
        }
        Path value = Path.of(configured);
        return (value.isAbsolute() ? value : startupWorkspace.resolve(value)).toAbsolutePath().normalize();
    }

    private String safeId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid conversation id");
        }
        return value;
    }
}
