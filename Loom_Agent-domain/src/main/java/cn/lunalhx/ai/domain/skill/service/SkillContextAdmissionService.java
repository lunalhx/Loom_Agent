package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextProperties;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import cn.lunalhx.ai.domain.agent.service.prompt.StablePrefixBuilder;
import cn.lunalhx.ai.domain.agent.service.workspace.WorkspaceFacts;
import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.skill.model.SkillActivationException;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * Verifies a candidate active-Skill set before it becomes Run state. The
 * calculation reserves the immutable complete prefix, all dynamic-section
 * floors, and the untrimmed current request; it therefore never admits a
 * body that ContextManager would have to clip.
 */
public final class SkillContextAdmissionService {

    private final AgentRuntimeProperties properties;
    private final ToolRegistry toolRegistry;
    private final StablePrefixBuilder prefixBuilder = new StablePrefixBuilder();
    private final ContextManager contextManager;

    public SkillContextAdmissionService(AgentRuntimeProperties properties,
                                        ToolRegistry toolRegistry) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.contextManager = new ContextManager(this.properties);
    }

    public List<ToolSpec> assertAdmitted(AgentContext context,
                                         List<ActiveSkillSnapshot> candidateActive) {
        ContextProperties contextProperties = context.runtimeProperties(properties).getContext();
        List<ToolSpec> candidateTools = SkillToolCatalogProjector.project(
                context, toolRegistry, candidateActive);
        if (!Boolean.TRUE.equals(contextProperties.getContextReductionEnabled())) {
            return candidateTools;
        }

        String candidatePrefix = renderedPrefix(context, candidateActive, candidateTools);
        int minimumContextChars = contextManager.minimumContextChars(context, candidatePrefix);
        if (minimumContextChars > totalBudgetChars(contextProperties)) {
            throw new SkillActivationException(
                    "active Skill instructions exceed the Run context budget");
        }
        return candidateTools;
    }

    private String renderedPrefix(AgentContext context,
                                  List<ActiveSkillSnapshot> active,
                                  List<ToolSpec> tools) {
        boolean isDelegate = StringUtils.isNotBlank(context.getParentRunId());
        boolean delegateAllowed = StringUtils.isBlank(context.getParentRunId());
        String pathScope = StringUtils.isBlank(context.getPathScope()) ? null : context.getPathScope();
        CollaborationMode mode = context.getCollaborationMode() == null
                ? CollaborationMode.BUILD : context.getCollaborationMode();
        return prefixBuilder.build(isDelegate, delegateAllowed, pathScope, tools,
                workspaceIdentity(context), null, mode, context.getPlanBinding(),
                context.getSkillCatalogSnapshot(), active).frozenContent();
    }

    private String workspaceIdentity(AgentContext context) {
        boolean includeWorkspaceFacts = context.getRunConfig() != null
                && context.getRunConfig().agent() != null
                && context.getRunConfig().agent().getFeatureFlags() != null
                && context.getRunConfig().agent().getFeatureFlags().stablePrefixWorkspaceFacts();
        if (!includeWorkspaceFacts || context.getResolvedWorkspace() == null) {
            return "";
        }
        try {
            return WorkspaceFacts.build(context.getResolvedWorkspace(), null).identityText();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int totalBudgetChars(ContextProperties properties) {
        Integer configured = properties.getTotalBudgetChars();
        return configured == null || configured <= 0 ? 12000 : configured;
    }
}
