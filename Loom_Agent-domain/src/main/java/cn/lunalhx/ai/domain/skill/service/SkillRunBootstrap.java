package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.skill.model.SkillActivationException;
import cn.lunalhx.ai.domain.skill.model.SkillCatalog;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Root-run Skill bootstrap: freeze catalog, resolve explicit {@code $skill-name} selectors,
 * activate snapshots, and rewrite the task text before the first model call.
 */
public final class SkillRunBootstrap {
    private final SkillDiscoveryService discovery = new SkillDiscoveryService();
    private final SkillSelectorParser selectorParser = new SkillSelectorParser();
    private final SkillActivationService activation = new SkillActivationService();

    public void prepareRootRun(AgentContext context, Path userHome) {
        Objects.requireNonNull(context, "context");
        if (context.getParentRunId() != null || context.getAgentDepth() > 0) {
            return;
        }
        Path workspace = context.getResolvedWorkspace();
        if (workspace == null) {
            throw new SkillActivationException("workspace is required for skill discovery");
        }
        Path home = userHome == null ? Path.of(System.getProperty("user.home")) : userHome;
        SkillCatalog catalog = discovery.discover(workspace, home);
        context.setSkillCatalogSnapshot(catalog);

        SkillSelectorParser.ParsedSelectors parsed = selectorParser.parse(context.getQuestion());
        if (!parsed.hadSelectors()) {
            context.setActiveSkills(List.of());
            return;
        }
        if (parsed.taskWithoutSelectors().isBlank()) {
            throw new SkillActivationException(
                    "task is empty after removing skill selectors");
        }
        List<ActiveSkillSnapshot> active = activation.activateExplicit(catalog, parsed.names());
        context.setActiveSkills(active);
        context.setQuestion(parsed.taskWithoutSelectors());
        if (context.getPendingContinuation() != null) {
            context.setPendingContinuation(parsed.taskWithoutSelectors());
        }
    }
}
