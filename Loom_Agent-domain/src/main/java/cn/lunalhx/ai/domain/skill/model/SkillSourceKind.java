package cn.lunalhx.ai.domain.skill.model;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Configured Skill Source root and its deterministic precedence. */
public enum SkillSourceKind {
    USER_AGENTS("user .agents", 0),
    USER_CLAUDE("user .claude", 1),
    PROJECT_AGENTS("project .agents", 2),
    PROJECT_CLAUDE("project .claude", 3);

    private final String scopeLabel;
    private final int precedence;

    SkillSourceKind(String scopeLabel, int precedence) {
        this.scopeLabel = scopeLabel;
        this.precedence = precedence;
    }

    public String scopeLabel() {
        return scopeLabel;
    }

    public int precedence() {
        return precedence;
    }

    public static List<SkillSourceRoot> configuredRoots(Path workspace, Path userHome) {
        return List.of(
                new SkillSourceRoot(USER_AGENTS, userHome.resolve(".agents/skills")),
                new SkillSourceRoot(USER_CLAUDE, userHome.resolve(".claude/skills")),
                new SkillSourceRoot(PROJECT_AGENTS, workspace.resolve(".agents/skills")),
                new SkillSourceRoot(PROJECT_CLAUDE, workspace.resolve(".claude/skills")));
    }

    public record SkillSourceRoot(SkillSourceKind kind, Path root) implements Comparable<SkillSourceRoot> {
        @Override
        public int compareTo(SkillSourceRoot other) {
            return Comparator.comparingInt((SkillSourceRoot value) -> value.kind().precedence())
                    .thenComparing(value -> value.root().toString())
                    .compare(this, other);
        }

        public String labelFor(Path packageDir) {
            return kind.scopeLabel() + "/skills/" + packageDir.getFileName();
        }
    }
}
